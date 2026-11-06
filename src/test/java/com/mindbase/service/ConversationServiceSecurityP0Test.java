package com.mindbase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbase.exception.CustomException;
import com.mindbase.model.Conversation;
import com.mindbase.model.ConversationSession;
import com.mindbase.model.User;
import com.mindbase.repository.ConversationRepository;
import com.mindbase.repository.ConversationSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 安全 P0 轮回归（2026-11-07）：
 * P0-1 按 conversationId 读历史必须校验属主；P0-2 archive/unarchive 必须校验属主；
 * P0-3 OCR 回调 token 未配置时 fail-closed。风格与 ConversationServiceOwnershipTest 一致。
 */
class ConversationServiceSecurityP0Test {

    private static final String ADMIN_CONV = "8928f78d-21b1-4036-a3d8-d23d94c5c61b";
    private static final Long ADMIN_ID = 1L;
    private static final Long PROBE_ID = 2L;

    private ConversationSession ownedSession(Long ownerId) {
        User owner = new User();
        owner.setId(ownerId);
        ConversationSession session = new ConversationSession();
        session.setUser(owner);
        return session;
    }

    // ---- P0-1：读取路径 IDOR ----

    @Test
    void probeUserMustNotReadAdminConversationHistory() {
        ConversationSessionRepository sessionRepository = mock(ConversationSessionRepository.class);
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        ConversationService service = new ConversationService();
        ReflectionTestUtils.setField(service, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(service, "conversationRepository", conversationRepository);

        when(sessionRepository.findByConversationId(ADMIN_CONV)).thenReturn(Optional.of(ownedSession(ADMIN_ID)));

        // 越权：probe 读 admin 会话被拒
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(CustomException.class,
                () -> service.getMessagesByConversationId(PROBE_ID, ADMIN_CONV)).getStatus());
        // 越权请求不得触达消息查询
        verify(conversationRepository, never()).findByConversationIdOrderByTimestampAsc(anyString());
    }

    @Test
    void ownerCanReadOwnConversationHistory() {
        ConversationSessionRepository sessionRepository = mock(ConversationSessionRepository.class);
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        ConversationService service = new ConversationService();
        ReflectionTestUtils.setField(service, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(service, "conversationRepository", conversationRepository);

        Conversation message = new Conversation();
        message.setConversationId(ADMIN_CONV);
        message.setQuestion("q");
        message.setAnswer("a");
        message.setTimestamp(LocalDateTime.now());
        when(sessionRepository.findByConversationId(ADMIN_CONV)).thenReturn(Optional.of(ownedSession(ADMIN_ID)));
        when(conversationRepository.findByConversationIdOrderByTimestampAsc(ADMIN_CONV)).thenReturn(List.of(message));

        // 属主正常路径：读取成功且返回消息（HTTP 200 语义；1 条对话还原为 user+assistant 2 条消息）
        List<Map<String, Object>> messages =
                assertDoesNotThrow(() -> service.getMessagesByConversationId(ADMIN_ID, ADMIN_CONV));
        assertEquals(2, messages.size());
    }

    // ---- P0-2：archive/unarchive IDOR ----

    @Test
    void probeUserMustNotArchiveOrUnarchiveAdminConversation() {
        ConversationSessionRepository sessionRepository = mock(ConversationSessionRepository.class);
        ConversationService service = new ConversationService();
        ReflectionTestUtils.setField(service, "sessionRepository", sessionRepository);

        when(sessionRepository.findByConversationId(ADMIN_CONV)).thenReturn(Optional.of(ownedSession(ADMIN_ID)));

        // 越权：probe 归档/取消归档 admin 会话均被拒
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(CustomException.class,
                () -> service.archiveConversationSession(PROBE_ID, ADMIN_CONV)).getStatus());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(CustomException.class,
                () -> service.unarchiveConversationSession(PROBE_ID, ADMIN_CONV)).getStatus());
        // 越权请求不得改写会话状态
        verify(sessionRepository, never()).save(any(ConversationSession.class));
    }

    @Test
    void ownerCanArchiveAndUnarchiveOwnConversation() {
        ConversationSessionRepository sessionRepository = mock(ConversationSessionRepository.class);
        ConversationService service = new ConversationService();
        ReflectionTestUtils.setField(service, "sessionRepository", sessionRepository);

        ConversationSession session = ownedSession(ADMIN_ID);
        when(sessionRepository.findByConversationId(ADMIN_CONV)).thenReturn(Optional.of(session));

        // 属主正常路径：归档→ARCHIVED，取消归档→ACTIVE
        assertDoesNotThrow(() -> service.archiveConversationSession(ADMIN_ID, ADMIN_CONV));
        assertEquals(ConversationSession.SessionStatus.ARCHIVED, session.getStatus());
        assertDoesNotThrow(() -> service.unarchiveConversationSession(ADMIN_ID, ADMIN_CONV));
        assertEquals(ConversationSession.SessionStatus.ACTIVE, session.getStatus());
        verify(sessionRepository, times(2)).save(session);
    }

    // ---- P0 复扫补充：switch 指针同样必须属主 ----

    @Test
    void switchPointerMustBelongToOwner() {
        ConversationSessionRepository sessionRepository = mock(ConversationSessionRepository.class);
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
        ConversationService service = new ConversationService();
        ReflectionTestUtils.setField(service, "sessionRepository", sessionRepository);
        ReflectionTestUtils.setField(service, "redisTemplate", org.mockito.Mockito.mock(org.springframework.data.redis.core.RedisTemplate.class));
        // redisTemplate.opsForValue() 需要打桩
        org.springframework.data.redis.core.RedisTemplate<String, String> redisTemplate =
                (org.springframework.data.redis.core.RedisTemplate<String, String>) ReflectionTestUtils.getField(service, "redisTemplate");
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        // 越权：probe 切换到 admin 会话 → 403，且指针不被写入
        when(sessionRepository.findByConversationId(ADMIN_CONV)).thenReturn(Optional.of(ownedSession(ADMIN_ID)));
        CustomException foreign = assertThrows(CustomException.class,
                () -> service.switchCurrentConversation(999L, ADMIN_CONV));
        assertEquals(HttpStatus.FORBIDDEN, foreign.getStatus());
        verify(valueOps, never()).set(anyString(), anyString(), any(java.time.Duration.class));

        // 不存在 → 404
        when(sessionRepository.findByConversationId("no-such")).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(CustomException.class,
                () -> service.switchCurrentConversation(999L, "no-such")).getStatus());

        // 属主正常：指针写入
        assertDoesNotThrow(() -> service.switchCurrentConversation(ADMIN_ID, ADMIN_CONV));
        verify(valueOps, times(1)).set(anyString(), anyString(), any(java.time.Duration.class));
    }

    // ---- P0-3：OCR 回调 fail-closed ----

    private AliyunOcrService ocrServiceWithToken(String callbackToken) {
        return new AliyunOcrService(new ObjectMapper(), false, null, "", "", null,
                "points", true, false, false, false, 10000, 60000, callbackToken);
    }

    @Test
    void ocrCallbackMustFailClosedWhenTokenNotConfigured() {
        AliyunOcrService service = ocrServiceWithToken("");

        // 未配置 callback-token：无论请求带什么 token 一律拒绝（不再放行）
        ResponseStatusException rejectAnonymous = assertThrows(ResponseStatusException.class,
                () -> service.verifyCallbackToken(null));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, rejectAnonymous.getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> service.verifyCallbackToken("anything")).getStatusCode());
        // 未配置时对匿名调用者不暴露任何配置信息
        assertFalse(service.isCallbackTokenRequired());
    }

    @Test
    void ocrCallbackAcceptsOnlyCorrectConfiguredToken() {
        AliyunOcrService service = ocrServiceWithToken("s3cret");

        // 配置后：错 token 拒绝、对 token 拒绝、对 token 放行
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> service.verifyCallbackToken("wrong")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> service.verifyCallbackToken(null)).getStatusCode());
        assertDoesNotThrow(() -> service.verifyCallbackToken("s3cret"));
    }
}
