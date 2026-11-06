package com.mindbase.controller;

import com.mindbase.model.FileUpload;
import com.mindbase.service.ChatGenerationStateService;
import com.mindbase.service.ChatHandler;
import com.mindbase.service.DocumentService;
import com.mindbase.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 安全 P1a 轮回归（2026-11-07）：/documents/reference-detail 会话属主防线（评估 P1#2）。
 * sessionId 即聊天生成任务 generationId：先认证 → 再校验生成任务属主
 * （对齐 ChatController.getGeneration 的 getGenerationForUser 与
 * ConversationService.validateConversationOwner 家族语义：不存在 404、非属主 403），
 * 授权通过前不得触碰引用映射；属主仍需通过既有文件级可访问校验。
 * 风格与 ConversationServiceSecurityP0Test 一致。
 */
class DocumentControllerReferenceDetailSecurityTest {

    private static final String GEN_ID = "gen-0f3e-uuid";
    private static final String ADMIN_USER = "1";
    private static final String PROBE_USER = "2";
    private static final String FILE_MD5 = "md5-owned-by-admin";

    private DocumentController controllerWith(JwtUtils jwtUtils,
                                              ChatGenerationStateService stateService,
                                              ChatHandler chatHandler,
                                              DocumentService documentService) {
        DocumentController controller = new DocumentController();
        ReflectionTestUtils.setField(controller, "jwtUtils", jwtUtils);
        ReflectionTestUtils.setField(controller, "chatGenerationStateService", stateService);
        ReflectionTestUtils.setField(controller, "chatHandler", chatHandler);
        ReflectionTestUtils.setField(controller, "documentService", documentService);
        return controller;
    }

    private ChatGenerationStateService.GenerationSnapshot generationOwnedBy(String userId) {
        return new ChatGenerationStateService.GenerationSnapshot(
                GEN_ID, userId, "conv-1", "问题", ChatGenerationStateService.GenerationStatus.COMPLETED,
                "答案", "2026-11-07T10:00", "2026-11-07T10:01", null, null, Map.of());
    }

    private ChatHandler.ReferenceInfo referenceInfo() {
        return new ChatHandler.ReferenceInfo(FILE_MD5, "机密文件.pdf", 3,
                "锚点文本", "hybrid", "混合检索", "检索 query",
                "命中分块文本", "证据片段", 0.87, 42, "SUPPORTED", null);
    }

    // ---- 越权拒 ----

    @Test
    void anonymousMustBeRejectedBeforeTouchingReferenceStore() {
        JwtUtils jwtUtils = mock(JwtUtils.class);
        ChatGenerationStateService stateService = mock(ChatGenerationStateService.class);
        ChatHandler chatHandler = mock(ChatHandler.class);
        DocumentService documentService = mock(DocumentService.class);
        DocumentController controller = controllerWith(jwtUtils, stateService, chatHandler, documentService);

        ResponseEntity<?> response = controller.getReferenceDetail(GEN_ID, 1, null);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        // 未认证请求不得触达生成状态与引用映射
        verify(stateService, never()).getGeneration(anyString());
        verify(chatHandler, never()).getReferenceDetail(anyString(), anyInt());
    }

    @Test
    void probeUserMustNotReadAdminGenerationReferenceDetail() {
        JwtUtils jwtUtils = mock(JwtUtils.class);
        when(jwtUtils.extractUserIdFromToken("probe-token")).thenReturn(PROBE_USER);
        when(jwtUtils.extractOrgTagsFromToken("probe-token")).thenReturn("");
        ChatGenerationStateService stateService = mock(ChatGenerationStateService.class);
        when(stateService.getGeneration(GEN_ID)).thenReturn(Optional.of(generationOwnedBy(ADMIN_USER)));
        ChatHandler chatHandler = mock(ChatHandler.class);
        DocumentService documentService = mock(DocumentService.class);
        DocumentController controller = controllerWith(jwtUtils, stateService, chatHandler, documentService);

        ResponseEntity<?> response =
                controller.getReferenceDetail(GEN_ID, 1, "Bearer probe-token");

        // 越权：非属主 403（与 validateConversationOwner 家族语义一致）
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        // 越权请求不得触达引用映射，也不得触发文件可访问查询
        verify(chatHandler, never()).getReferenceDetail(anyString(), anyInt());
        verify(documentService, never()).getAccessibleFiles(anyString(), anyString());
    }

    @Test
    void missingGenerationReturns404WithoutTouchingReferenceStore() {
        JwtUtils jwtUtils = mock(JwtUtils.class);
        when(jwtUtils.extractUserIdFromToken("probe-token")).thenReturn(PROBE_USER);
        when(jwtUtils.extractOrgTagsFromToken("probe-token")).thenReturn("");
        ChatGenerationStateService stateService = mock(ChatGenerationStateService.class);
        when(stateService.getGeneration(GEN_ID)).thenReturn(Optional.empty());
        ChatHandler chatHandler = mock(ChatHandler.class);
        DocumentController controller = controllerWith(jwtUtils, stateService, chatHandler, mock(DocumentService.class));

        ResponseEntity<?> response =
                controller.getReferenceDetail(GEN_ID, 1, "Bearer probe-token");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(chatHandler, never()).getReferenceDetail(anyString(), anyInt());
    }

    // ---- 属主通 ----

    @Test
    void ownerCanReadOwnReferenceDetailWithinAccessibleFiles() {
        JwtUtils jwtUtils = mock(JwtUtils.class);
        when(jwtUtils.extractUserIdFromToken("admin-token")).thenReturn(ADMIN_USER);
        when(jwtUtils.extractOrgTagsFromToken("admin-token")).thenReturn("");
        ChatGenerationStateService stateService = mock(ChatGenerationStateService.class);
        when(stateService.getGeneration(GEN_ID)).thenReturn(Optional.of(generationOwnedBy(ADMIN_USER)));
        ChatHandler chatHandler = mock(ChatHandler.class);
        when(chatHandler.getReferenceDetail(GEN_ID, 1)).thenReturn(referenceInfo());
        DocumentService documentService = mock(DocumentService.class);
        FileUpload ownedFile = new FileUpload();
        ownedFile.setFileMd5(FILE_MD5);
        when(documentService.getAccessibleFiles(ADMIN_USER, "")).thenReturn(List.of(ownedFile));
        DocumentController controller = controllerWith(jwtUtils, stateService, chatHandler, documentService);

        ResponseEntity<?> response =
                controller.getReferenceDetail(GEN_ID, 1, "Bearer admin-token");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(200, body.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertEquals(FILE_MD5, data.get("fileMd5"));
        assertEquals("机密文件.pdf", data.get("fileName"));
        assertEquals(1, data.get("referenceNumber"));
    }

    @Test
    void ownerStillBlockedWhenReferencedFileNotAccessible() {
        JwtUtils jwtUtils = mock(JwtUtils.class);
        when(jwtUtils.extractUserIdFromToken("admin-token")).thenReturn(ADMIN_USER);
        when(jwtUtils.extractOrgTagsFromToken("admin-token")).thenReturn("");
        ChatGenerationStateService stateService = mock(ChatGenerationStateService.class);
        when(stateService.getGeneration(GEN_ID)).thenReturn(Optional.of(generationOwnedBy(ADMIN_USER)));
        ChatHandler chatHandler = mock(ChatHandler.class);
        when(chatHandler.getReferenceDetail(GEN_ID, 1)).thenReturn(referenceInfo());
        DocumentService documentService = mock(DocumentService.class);
        when(documentService.getAccessibleFiles(ADMIN_USER, "")).thenReturn(List.of());
        DocumentController controller = controllerWith(jwtUtils, stateService, chatHandler, documentService);

        // 属主但引用文件已不在可访问集合：既有文件级防线保持 403
        ResponseEntity<?> response =
                controller.getReferenceDetail(GEN_ID, 1, "Bearer admin-token");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }
}
