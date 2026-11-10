package com.mindbase.security;

import com.mindbase.utils.HttpRequestUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 客户端 IP 信任策略单测（task-0019 / C19）。
 *
 * <p>核心保证：直连对端（remoteAddr）不受信时，任何转发头（伪造的
 * CF-Connecting-IP / X-Forwarded-For / X-Real-IP）一律被忽略，登录限流
 * 不可用伪造头绕过；只有 remoteAddr 命中受信代理列表时才按 XFF 链右向左解析；
 * 所有解析失败一律回落 remoteAddr，不返回攻击者可控值。
 *
 * <p>纯内存单测（MockHttpServletRequest），不依赖任何外部服务。
 */
class ClientIpResolverTest {

    private static final String TRUSTED_V4_CIDR = "10.0.0.0/8";

    @AfterEach
    void resetStaticDelegate() {
        // 避免静态桥接泄漏到其他测试；未注册时 HttpRequestUtil 保守回落 remoteAddr
        HttpRequestUtil.setClientIpResolver(null);
    }

    private static MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    // ---- 场景 1：未受信 remote + 伪造 CF/XFF → remoteAddr ----

    @Test
    void untrustedRemoteIgnoresAllForgedForwardedHeaders() {
        ClientIpResolver resolver = new ClientIpResolver(TRUSTED_V4_CIDR);
        MockHttpServletRequest request = request("203.0.113.5");
        request.addHeader("CF-Connecting-IP", "1.2.3.4");
        request.addHeader("X-Forwarded-For", "9.9.9.9");
        request.addHeader("X-Real-IP", "8.8.8.8");
        request.addHeader("True-Client-IP", "7.7.7.7");
        assertEquals("203.0.113.5", resolver.resolveClientIp(request));
    }

    @Test
    void emptyTrustedProxyConfigNeverTrustsForwardedHeaders() {
        // 默认空配置：不信任任何直连来源
        ClientIpResolver resolver = new ClientIpResolver("");
        MockHttpServletRequest request = request("198.51.100.2");
        request.addHeader("CF-Connecting-IP", "1.2.3.4");
        request.addHeader("X-Forwarded-For", "9.9.9.9");
        assertEquals("198.51.100.2", resolver.resolveClientIp(request));
    }

    @Test
    void untrustedRemoteWithoutHeadersReturnsRemoteAddr() {
        ClientIpResolver resolver = new ClientIpResolver(TRUSTED_V4_CIDR);
        MockHttpServletRequest request = request("203.0.113.5");
        assertEquals("203.0.113.5", resolver.resolveClientIp(request));
    }

    // ---- 场景 2：受信代理 + `client, proxy` → client ----

    @Test
    void trustedProxyWalksXForwardedForRightToLeft() {
        ClientIpResolver resolver = new ClientIpResolver(TRUSTED_V4_CIDR);
        MockHttpServletRequest request = request("10.0.0.2");
        request.addHeader("X-Forwarded-For", "198.51.100.7, 10.0.0.2");
        assertEquals("198.51.100.7", resolver.resolveClientIp(request));
    }

    @Test
    void trustedProxySkipsForgedLeftEntriesOnlyAfterFirstUntrusted() {
        // 攻击者伪造的最左条目不可达：第一个不受信地址在受信代理右侧
        ClientIpResolver resolver = new ClientIpResolver(TRUSTED_V4_CIDR);
        MockHttpServletRequest request = request("10.0.0.2");
        request.addHeader("X-Forwarded-For", "6.6.6.6, 198.51.100.7, 10.0.0.2");
        assertEquals("198.51.100.7", resolver.resolveClientIp(request));
    }

    @Test
    void multipleTrustedProxiesAreSkipped() {
        // 混合精确地址与 CIDR：172.16.5.4 命中 172.16.0.0/12，192.168.1.7 精确命中
        ClientIpResolver resolver = new ClientIpResolver("192.168.1.7, 172.16.0.0/12");
        MockHttpServletRequest request = request("192.168.1.7");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 172.16.5.4");
        assertEquals("203.0.113.9", resolver.resolveClientIp(request));
    }

    // ---- 场景 3：全受信链 → 最左 ----

    @Test
    void fullyTrustedChainReturnsLeftmostAddress() {
        ClientIpResolver resolver = new ClientIpResolver(TRUSTED_V4_CIDR);
        MockHttpServletRequest request = request("10.0.0.3");
        request.addHeader("X-Forwarded-For", "10.0.0.1, 10.0.0.2, 10.0.0.3");
        assertEquals("10.0.0.1", resolver.resolveClientIp(request));
    }

    @Test
    void singleTrustedXffEntryEqualsRemoteAddrReturnsIt() {
        ClientIpResolver resolver = new ClientIpResolver(TRUSTED_V4_CIDR);
        MockHttpServletRequest request = request("10.0.0.2");
        request.addHeader("X-Forwarded-For", "10.0.0.2");
        assertEquals("10.0.0.2", resolver.resolveClientIp(request));
    }

    // ---- 场景 4：非法头回落 remoteAddr ----

    @Test
    void illegalXffEntryFallsBackToRemoteAddr() {
        ClientIpResolver resolver = new ClientIpResolver(TRUSTED_V4_CIDR);
        MockHttpServletRequest request = request("10.0.0.2");
        request.addHeader("X-Forwarded-For", "not-an-ip");
        assertEquals("10.0.0.2", resolver.resolveClientIp(request));
    }

    @Test
    void illegalEntryRightOfClientFallsBackToRemoteAddr() {
        // 非法条目位于受信代理与“客户端”之间：链不完整，不可采信更左侧的值
        ClientIpResolver resolver = new ClientIpResolver(TRUSTED_V4_CIDR);
        MockHttpServletRequest request = request("10.0.0.2");
        request.addHeader("X-Forwarded-For", "198.51.100.7, 999.999.999.999, 10.0.0.2");
        assertEquals("10.0.0.2", resolver.resolveClientIp(request));
    }

    @Test
    void illegalXRealIpFallsBackToRemoteAddr() {
        ClientIpResolver resolver = new ClientIpResolver(TRUSTED_V4_CIDR);
        MockHttpServletRequest request = request("10.0.0.2");
        request.addHeader("X-Real-IP", "999.999.999.999");
        assertEquals("10.0.0.2", resolver.resolveClientIp(request));
    }

    @Test
    void illegalCfConnectingIpFallsBackToRemoteAddr() {
        ClientIpResolver resolver = new ClientIpResolver(TRUSTED_V4_CIDR);
        MockHttpServletRequest request = request("10.0.0.2");
        request.addHeader("CF-Connecting-IP", "<script>alert(1)</script>");
        assertEquals("10.0.0.2", resolver.resolveClientIp(request));
    }

    @Test
    void unknownPlaceholderHeaderFallsBackToRemoteAddr() {
        ClientIpResolver resolver = new ClientIpResolver(TRUSTED_V4_CIDR);
        MockHttpServletRequest request = request("10.0.0.2");
        request.addHeader("X-Real-IP", "unknown");
        assertEquals("10.0.0.2", resolver.resolveClientIp(request));
    }

    // ---- 场景 5：无 XFF 时才看 X-Real-IP / CF-Connecting-IP ----

    @Test
    void trustedProxyWithoutXffUsesXRealIp() {
        ClientIpResolver resolver = new ClientIpResolver(TRUSTED_V4_CIDR);
        MockHttpServletRequest request = request("10.0.0.2");
        request.addHeader("X-Real-IP", "198.51.100.7");
        assertEquals("198.51.100.7", resolver.resolveClientIp(request));
    }

    @Test
    void trustedProxyUsesCfConnectingIpWhenRealIpAbsent() {
        ClientIpResolver resolver = new ClientIpResolver(TRUSTED_V4_CIDR);
        MockHttpServletRequest request = request("10.0.0.2");
        request.addHeader("CF-Connecting-IP", "198.51.100.7");
        assertEquals("198.51.100.7", resolver.resolveClientIp(request));
    }

    @Test
    void xffTakesPrecedenceOverRealIpAndCfHeaders() {
        // XFF 存在且可解析时不看 X-Real-IP / CF-Connecting-IP
        ClientIpResolver resolver = new ClientIpResolver(TRUSTED_V4_CIDR);
        MockHttpServletRequest request = request("10.0.0.2");
        request.addHeader("X-Forwarded-For", "198.51.100.7, 10.0.0.2");
        request.addHeader("X-Real-IP", "8.8.8.8");
        request.addHeader("CF-Connecting-IP", "9.9.9.9");
        assertEquals("198.51.100.7", resolver.resolveClientIp(request));
    }

    @Test
    void blankXffIsTreatedAsAbsent() {
        ClientIpResolver resolver = new ClientIpResolver(TRUSTED_V4_CIDR);
        MockHttpServletRequest request = request("10.0.0.2");
        request.addHeader("X-Forwarded-For", "   ");
        request.addHeader("X-Real-IP", "198.51.100.7");
        assertEquals("198.51.100.7", resolver.resolveClientIp(request));
    }

    // ---- 场景 6：IPv6 精确匹配 ----

    @Test
    void ipv6ExactMatchTrustsProxyAndWalksXff() {
        // remoteAddr 为 ::1 的展开形式，配置为压缩形式 → 字节级等值命中
        ClientIpResolver resolver = new ClientIpResolver("::1, fe80::1");
        MockHttpServletRequest request = request("0:0:0:0:0:0:0:1");
        request.addHeader("X-Forwarded-For", "2001:db8::5, ::1");
        assertEquals("2001:db8::5", resolver.resolveClientIp(request));
    }

    @Test
    void untrustedIpv6RemoteIgnoresForwardedHeaders() {
        ClientIpResolver resolver = new ClientIpResolver("::1");
        MockHttpServletRequest request = request("2001:db8::5");
        request.addHeader("CF-Connecting-IP", "1.2.3.4");
        request.addHeader("X-Forwarded-For", "9.9.9.9");
        assertEquals("2001:db8::5", resolver.resolveClientIp(request));
    }

    @Test
    void ipv4MappedIpv6ClientAddressIsParsed() {
        ClientIpResolver resolver = new ClientIpResolver("::1");
        MockHttpServletRequest request = request("::1");
        request.addHeader("X-Forwarded-For", "::ffff:198.51.100.7, ::1");
        assertEquals("::ffff:198.51.100.7", resolver.resolveClientIp(request));
    }

    // ---- 场景 7：不支持的受信代理形式 → warn 并跳过 ----

    @Test
    void unsupportedIpv6CidrEntryIsSkippedSoRemoteIsUntrusted() {
        // IPv6 CIDR 不支持：该条目被跳过后列表为空，remoteAddr 不受信
        ClientIpResolver resolver = new ClientIpResolver("fe80::/10");
        MockHttpServletRequest request = request("fe80::1");
        request.addHeader("CF-Connecting-IP", "1.2.3.4");
        assertEquals("fe80::1", resolver.resolveClientIp(request));
    }

    @Test
    void illegalPrefixLengthEntryIsSkipped() {
        // /33 超出 IPv4 前缀范围：跳过该条目，不影响其余条目
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/33, 10.0.0.0/8");
        MockHttpServletRequest request = request("10.0.0.2");
        request.addHeader("X-Forwarded-For", "198.51.100.7, 10.0.0.2");
        assertEquals("198.51.100.7", resolver.resolveClientIp(request));
    }

    @Test
    void hostnameEntryIsSkippedSoRemoteIsUntrusted() {
        // 主机名形式的条目不支持（也绝不触发 DNS 解析）
        ClientIpResolver resolver = new ClientIpResolver("proxy.example.com");
        MockHttpServletRequest request = request("203.0.113.5");
        request.addHeader("X-Forwarded-For", "9.9.9.9");
        assertEquals("203.0.113.5", resolver.resolveClientIp(request));
    }

    // ---- 场景 8：HttpRequestUtil 委托同一实现 ----

    @Test
    void httpRequestUtilDelegatesToRegisteredResolver() {
        ClientIpResolver resolver = new ClientIpResolver(TRUSTED_V4_CIDR);
        HttpRequestUtil.setClientIpResolver(resolver);
        MockHttpServletRequest request = request("203.0.113.5");
        request.addHeader("X-Forwarded-For", "9.9.9.9");
        request.addHeader("CF-Connecting-IP", "1.2.3.4");
        assertEquals("203.0.113.5", HttpRequestUtil.resolveClientIp(request));
    }

    @Test
    void httpRequestUtilDelegationTrustsForwardedHeadersOnlyFromTrustedProxy() {
        ClientIpResolver resolver = new ClientIpResolver(TRUSTED_V4_CIDR);
        HttpRequestUtil.setClientIpResolver(resolver);
        MockHttpServletRequest request = request("10.0.0.2");
        request.addHeader("X-Forwarded-For", "198.51.100.7, 10.0.0.2");
        assertEquals("198.51.100.7", HttpRequestUtil.resolveClientIp(request));
    }

    // ---- 边界：缺 remoteAddr / null request ----

    @Test
    void nullRequestReturnsUnknown() {
        ClientIpResolver resolver = new ClientIpResolver(TRUSTED_V4_CIDR);
        assertEquals("unknown", resolver.resolveClientIp(null));
    }

    @Test
    void nullRequestWithoutResolverReturnsUnknown() {
        assertEquals("unknown", HttpRequestUtil.resolveClientIp(null));
    }
}
