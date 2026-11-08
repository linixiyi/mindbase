package com.mindbase.controller;

import com.mindbase.config.JwtAuthenticationFilter;
import com.mindbase.config.OrgTagAuthorizationFilter;
import com.mindbase.config.SecurityConfig;
import com.mindbase.repository.FileUploadRepository;
import com.mindbase.service.CustomUserDetailsService;
import com.mindbase.service.TokenCacheService;
import com.mindbase.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * refresh token 轮换与撤销（task_0003）
 *
 * 覆盖任务书成功判定：
 * 1. 无 Authorization 头时用有效 refresh 换发返回 200（SecurityConfig 对 /api/v1/auth/refreshToken 精确 permitAll）
 * 2. 同一 refresh 第二次换发返回 401（换发成功后旧 refresh 已被吊销）
 * 3. logout-all 后换发返回 401（refresh family 被吊销）
 * 4. 签名伪造返回 401
 *
 * 按任务书失败处理：@WebMvcTest(AuthController.class) 切片 + mock JwtUtils/TokenCacheService，
 * 不拉起完整上下文、不依赖真实 Redis；SecurityConfig 及其两个过滤器真实装配（依赖全部 mock），
 * 以便让 permitAll 规则真实生效。
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, OrgTagAuthorizationFilter.class})
class TokenRotationTest {

    private static final String REFRESH_URL = "/api/v1/auth/refreshToken";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private TokenCacheService tokenCacheService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @MockitoBean
    private FileUploadRepository fileUploadRepository;

    private void stubValidRefresh(String refreshToken) {
        when(jwtUtils.validateRefreshToken(refreshToken)).thenReturn(true);
        when(jwtUtils.extractUsernameFromToken(refreshToken)).thenReturn("alice");
        when(jwtUtils.extractRefreshTokenIdFromToken(refreshToken)).thenReturn("oldrefreshid");
        when(jwtUtils.extractUserIdFromToken(refreshToken)).thenReturn("42");
        when(jwtUtils.generateToken("alice")).thenReturn("new-access-token");
        when(jwtUtils.generateRefreshToken("alice")).thenReturn("new-refresh-token");
    }

    /**
     * 成功判定 1：无 Authorization 头 + 有效 refresh → 200，
     * 且换发成功后旧 refresh 被吊销（防重放）
     */
    @Test
    void refreshWithoutAuthorizationHeaderWithValidRefreshTokenReturns200AndRevokesOld() throws Exception {
        stubValidRefresh("valid-refresh-token");

        mockMvc.perform(post(REFRESH_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"valid-refresh-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").value("new-access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("new-refresh-token"));

        verify(tokenCacheService).revokeRefreshToken("42", "oldrefreshid");
    }

    /**
     * 成功判定 2：同一 refresh 第二次换发返回 401。
     * 第一次换发后旧 refresh 已被吊销，validateRefreshToken 对其返回 false（等价于 Redis 键已删除）。
     */
    @Test
    void sameRefreshTokenSecondTimeReturns401() throws Exception {
        String refreshToken = "replayed-refresh-token";
        stubValidRefresh(refreshToken);

        mockMvc.perform(post(REFRESH_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk());

        // 第二次使用：旧 refresh 已被吊销，校验失败
        when(jwtUtils.validateRefreshToken(refreshToken)).thenReturn(false);

        mockMvc.perform(post(REFRESH_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /**
     * 成功判定 3：logout-all 后（refresh family 已吊销，validateRefreshToken 返回 false）换发返回 401，
     * 且不再签发任何新 token
     */
    @Test
    void refreshAfterLogoutAllReturns401() throws Exception {
        when(jwtUtils.validateRefreshToken("revoked-family-refresh-token")).thenReturn(false);

        mockMvc.perform(post(REFRESH_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"revoked-family-refresh-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verify(jwtUtils, never()).generateToken(anyString());
        verify(jwtUtils, never()).generateRefreshToken(anyString());
    }

    /**
     * 成功判定 4：签名伪造的 refresh token → validateRefreshToken 捕获 SignatureException 返回 false → 401
     */
    @Test
    void forgedSignatureRefreshTokenReturns401() throws Exception {
        when(jwtUtils.validateRefreshToken("forged-signature-token")).thenReturn(false);

        mockMvc.perform(post(REFRESH_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"forged-signature-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verify(jwtUtils, never()).generateToken(anyString());
        verify(tokenCacheService, never()).revokeRefreshToken(anyString(), anyString());
    }

    /**
     * SecurityConfig 精确性：只放开 /api/v1/auth/refreshToken 单条路径，
     * 禁止整段 /api/v1/auth/** —— 同控制器下未列入 permitAll 的 /api/v1/auth/error 仍需认证。
     * 安全链未配置认证入口点，匿名请求被拒绝时返回 403（Http403ForbiddenEntryPoint 默认行为）
     */
    @Test
    void otherAuthPathsRemainProtected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/error?code=200&msg=x"))
                .andExpect(status().isForbidden());
    }
}
