package com.mindbase.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.RequiredTypeException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-0016：JWT 解析失败日志脱敏测试。
 *
 * <p>JwtUtils 五个 extract 方法（username/userId/role/orgTags/primaryOrg）的 ERROR 分支
 * 只有在 claims getter 抛异常时才会走到（内部 extractClaimsIgnoreExpiration 会把解析期异常
 * 降级为 DEBUG 并返回 null，所以纯乱码 token 触发不到 ERROR）。因此用注入密钥签发
 * “结构合法但目标 claim 为非 String 类型”的非法 token 依次调用 5 个 extract 方法。</p>
 *
 * <p>实测行为（jjwt 0.11.5，已用字节码核对）：{@code getSubject()} 走
 * {@code JwtMap.getString}（{@code String.valueOf} 宽松转换），sub 类型非法也不抛异常，
 * username 的 ERROR 分支经公开 API 不可达；其余 4 个 getter 走
 * {@code DefaultClaims.get(name, type) → castClaimValue} 抛 {@code RequiredTypeException}，
 * 各触发 1 处 ERROR。本测试因此断言“产生的每条 ERROR 日志都已脱敏”，共 4 条；
 * username 分支的脱敏改动同样随本次交付落地（JwtUtils 五处 log 全部替换）。</p>
 *
 * <p>断言：日志不含原文、含 {@code sha256:} 指纹；异常对象（类型与堆栈）不被脱敏吞掉；
 * TokenRedactor.fingerprint(null/blank) 返回 {@code empty}。</p>
 */
class JwtLogRedactionTest {

    /** 与生产一致的 Base64 密钥（48 字节，满足 HS256 ≥ 256bit 要求）。 */
    private static final String SECRET_BASE64 = Base64.getEncoder().encodeToString(
            "jwt-log-redaction-test-secret-key-0123456789abcdef0123456789abcdef"
                    .getBytes(StandardCharsets.UTF_8));

    private JwtUtils jwtUtils;
    private Logger jwtLogger;
    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "secretKeyBase64", SECRET_BASE64);

        jwtLogger = (Logger) LoggerFactory.getLogger(JwtUtils.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        jwtLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        jwtLogger.detachAppender(listAppender);
        listAppender.stop();
    }

    private static SecretKey signingKey() {
        return Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET_BASE64));
    }

    /**
     * 非法 token：签名有效、未过期，但 sub/userId/role/orgTags/primaryOrg 均为非 String 类型。
     * userId/role/orgTags/primaryOrg 四个 getter 走 castClaimValue 抛 RequiredTypeException，
     * 依次触发对应 4 处 ERROR 日志（生产中真实泄漏路径：orgTags 本就是 List）；
     * sub 经宽松的 getString 转换不抛异常（见类注释）。
     */
    private static String illegalClaimTypeToken() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", List.of("not-a-string"));
        claims.put("userId", Map.of("id", 1));
        claims.put("role", List.of("ADMIN"));
        claims.put("orgTags", List.of("org1", "org2"));
        claims.put("primaryOrg", Map.of("name", "org1"));
        return Jwts.builder()
                .setClaims(claims)
                .setExpiration(new Date(System.currentTimeMillis() + 3600_000L))
                .signWith(signingKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    @Test
    void fingerprintReturnsEmptyForNullOrBlank() {
        assertEquals("empty", TokenRedactor.fingerprint(null));
        assertEquals("empty", TokenRedactor.fingerprint(""));
        assertEquals("empty", TokenRedactor.fingerprint("   \t "));
    }

    @Test
    void fingerprintUsesSha256PrefixLengthAndIsDeterministic() throws Exception {
        String token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.signature-part";
        String fingerprint = TokenRedactor.fingerprint(token);

        assertTrue(fingerprint.startsWith("sha256:"), "指纹应以 sha256: 开头，实际: " + fingerprint);
        assertTrue(fingerprint.endsWith("…len=" + token.length()),
                "指纹应以 …len=<长度> 结尾，实际: " + fingerprint);

        String hex = fingerprint.substring("sha256:".length(), fingerprint.indexOf("…len="));
        assertTrue(hex.matches("[0-9a-f]{8}"), "应为 8 位小写十六进制，实际: " + hex);

        // 前 8 位十六进制必须等于 SHA-256 摘要的前 4 字节
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
        StringBuilder expectedHex = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            expectedHex.append(String.format("%02x", hash[i] & 0xff));
        }
        assertEquals(expectedHex.toString(), hex, "指纹必须是 SHA-256 派生，不可回退字符哈希");

        assertEquals(fingerprint, TokenRedactor.fingerprint(token), "同一 token 指纹应稳定");
        assertFalse(fingerprint.contains(token), "指纹不得包含原文");
    }

    @Test
    void fiveExtractMethodsWithIllegalToken_producedErrorLogsAreRedacted() {
        String illegalToken = illegalClaimTypeToken();
        String expectedFingerprint = TokenRedactor.fingerprint(illegalToken);

        // 依次触发 5 个 extract 方法（按卡面顺序）
        String username = jwtUtils.extractUsernameFromToken(illegalToken);
        String userId = jwtUtils.extractUserIdFromToken(illegalToken);
        String role = jwtUtils.extractRoleFromToken(illegalToken);
        String orgTags = jwtUtils.extractOrgTagsFromToken(illegalToken);
        String primaryOrg = jwtUtils.extractPrimaryOrgFromToken(illegalToken);

        // 把实测行为钉进测试：jjwt 0.11.5 的 getSubject() 走 JwtMap.getString（String.valueOf 宽松
        // 转换）不抛异常，username 分支不产生 ERROR；其余 4 个 getter 走 castClaimValue 抛
        // RequiredTypeException，各产生 1 条 ERROR。username 的脱敏改动已随本次交付落地，
        // 但其 ERROR 分支经公开 API 无法触发（详见类注释），故只能直接观察这 4 处。
        assertEquals("[not-a-string]", username, "getSubject() 的宽松转换行为应在测试中显式钉住");
        assertNull(userId);
        assertNull(role);
        assertNull(orgTags);
        assertNull(primaryOrg);

        List<ILoggingEvent> errors = listAppender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList();
        assertEquals(4, errors.size(),
                "4 个类型非法 getter 应各记 1 条 ERROR，实际捕获: "
                        + listAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());

        // 4 条日志分别来自 4 个不同的 log 语句（username 分支未触发，不应出现）
        String joined = errors.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(joined.contains("Error extracting userId from token"), joined);
        assertTrue(joined.contains("Error extracting role from token"), joined);
        assertTrue(joined.contains("Error extracting organization tags from token"), joined);
        assertTrue(joined.contains("Error extracting primary organization from token"), joined);

        // 复现异常类型：验证脱敏没有吞掉异常对象与类型
        Claims claims = Jwts.parserBuilder().setSigningKey(signingKey()).build()
                .parseClaimsJws(illegalToken).getBody();
        RequiredTypeException reproduced = org.junit.jupiter.api.Assertions.assertThrows(
                RequiredTypeException.class, () -> claims.get("userId", String.class));

        for (ILoggingEvent event : errors) {
            String message = event.getFormattedMessage();
            assertFalse(message.contains(illegalToken), "ERROR 日志不得包含原始 token: " + message);
            assertTrue(message.contains("sha256:"), "ERROR 日志应包含 sha256: 指纹: " + message);
            assertTrue(message.contains(expectedFingerprint),
                    "ERROR 日志应包含该 token 的指纹 " + expectedFingerprint + "，实际: " + message);

            IThrowableProxy throwableProxy = event.getThrowableProxy();
            assertNotNull(throwableProxy, "脱敏不得吞掉异常对象");
            assertEquals(reproduced.getClass().getName(), throwableProxy.getClassName(),
                    "脱敏不得改变异常类型");
            assertNotNull(throwableProxy.getMessage());
            assertFalse(String.valueOf(throwableProxy.getMessage()).contains(illegalToken),
                    "异常消息也不得包含原始 token");
            assertTrue(throwableProxy.getStackTraceElementProxyArray() != null
                            && throwableProxy.getStackTraceElementProxyArray().length > 0,
                    "脱敏不得吞掉异常堆栈");
        }
    }

    @Test
    void structurallyGarbageTokenNeverAppearsInAnyCapturedLog() {
        String garbageToken = "not-a-jwt-token";
        jwtUtils.extractUsernameFromToken(garbageToken);
        jwtUtils.extractUserIdFromToken(garbageToken);
        jwtUtils.extractRoleFromToken(garbageToken);
        jwtUtils.extractOrgTagsFromToken(garbageToken);
        jwtUtils.extractPrimaryOrgFromToken(garbageToken);

        // 乱码 token 在内部方法被降级为 DEBUG，不应产生 ERROR
        List<ILoggingEvent> errors = listAppender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList();
        assertEquals(0, errors.size());

        // 任何捕获到的日志（含 DEBUG）都不得包含乱码 token 原文
        for (ILoggingEvent event : listAppender.list) {
            assertFalse(event.getFormattedMessage().contains(garbageToken),
                    "任何日志都不得包含 token 原文: " + event.getFormattedMessage());
        }
    }
}
