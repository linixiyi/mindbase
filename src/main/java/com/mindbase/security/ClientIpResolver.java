package com.mindbase.security;

import com.mindbase.utils.HttpRequestUtil;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 客户端 IP 信任策略解析器（task-0019 / C19）。
 *
 * <p>背景：旧实现无条件采信 CF-Connecting-IP / X-Forwarded-For 等转发头，
 * 攻击者可伪造头绕过登录/注册的 IP 限流。策略收紧为：
 *
 * <ul>
 *   <li>直连对端（remoteAddr）不在受信代理列表内：任何转发头
 *       （X-Forwarded-For / X-Real-IP / CF-Connecting-IP）都视为攻击者可伪造，
 *       一律忽略，直接返回 remoteAddr；</li>
 *   <li>直连对端是受信代理：从 X-Forwarded-For 右向左跳过受信代理，
 *       取第一个不受信地址；全链受信时取最左侧地址；</li>
 *   <li>没有 XFF（或 XFF 为空白）时才依次采信 X-Real-IP 与 CF-Connecting-IP。</li>
 * </ul>
 *
 * <p>受信代理来自 {@code security.trusted-proxies}（逗号分隔），支持 IPv4 CIDR
 * 与 IPv6 精确地址；不支持的形式（如 IPv6 CIDR）记 warn 并跳过，默认空 =
 * 不信任任何直连来源。所有解析失败一律回落 remoteAddr，绝不返回攻击者可控值。
 *
 * <p>{@link HttpRequestUtil#resolveClientIp} 委托本实现（供 WxPay 等静态工具
 * 调用方使用），由 {@code @PostConstruct} 在 Spring 装配后注册桥接。
 */
@Slf4j
@Component
public class ClientIpResolver {

    static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";
    static final String HEADER_X_REAL_IP = "X-Real-IP";
    static final String HEADER_CF_CONNECTING_IP = "CF-Connecting-IP";

    private static final int IPV4_BYTES = 4;
    private static final int IPV6_BYTES = 16;

    private final List<TrustedProxy> trustedProxies;

    public ClientIpResolver(@Value("${security.trusted-proxies:}") String trustedProxiesConfig) {
        this.trustedProxies = parseTrustedProxies(trustedProxiesConfig);
    }

    @PostConstruct
    void registerStaticDelegate() {
        // 让 HttpRequestUtil（静态工具，供 WxPay 使用）与生产装配共用同一实现
        HttpRequestUtil.setClientIpResolver(this);
    }

    /**
     * 按信任策略解析客户端 IP。
     *
     * @param request 当前请求，可为 null（返回 unknown）
     * @return 客户端 IP；解析失败一律回落 remoteAddr，无 remoteAddr 时返回 unknown
     */
    public String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String remoteAddr = request.getRemoteAddr();
        byte[] remoteBytes = parseIp(remoteAddr);
        if (remoteBytes == null) {
            // remoteAddr 缺失或非法时没有任何可信基准，不采信任何转发头
            return "unknown";
        }
        if (!isTrusted(remoteBytes)) {
            // 直连对端不受信：转发头全部不可信（攻击者可随意伪造）
            return remoteAddr;
        }
        // 直连对端是受信代理：优先按 XFF 链右向左解析
        String xff = request.getHeader(HEADER_X_FORWARDED_FOR);
        if (xff != null && !xff.isBlank()) {
            String resolved = resolveFromForwardedFor(xff, remoteAddr);
            if (resolved != null) {
                return resolved;
            }
            // XFF 存在但不可用（含非法地址/全空槽位）：不再看其他转发头，回落 remoteAddr
            return remoteAddr;
        }
        // 无 XFF 时才依次采信 X-Real-IP 与 CF-Connecting-IP
        String realIp = trustedForwardedValue(request.getHeader(HEADER_X_REAL_IP), remoteAddr);
        if (realIp != null) {
            return realIp;
        }
        String cfIp = trustedForwardedValue(request.getHeader(HEADER_CF_CONNECTING_IP), remoteAddr);
        if (cfIp != null) {
            return cfIp;
        }
        return remoteAddr;
    }

    /**
     * 从 X-Forwarded-For 链解析：右向左跳过受信代理，取第一个不受信地址；
     * 全链受信取最左；链中含非法地址视为头被篡改，返回 null（调用方回落 remoteAddr）。
     */
    private String resolveFromForwardedFor(String xff, String remoteAddr) {
        String[] hops = xff.split(",");
        String firstUntrustedFromRight = null;
        String leftmostUsable = null;
        int usableCount = 0;
        for (int i = hops.length - 1; i >= 0; i--) {
            String candidate = hops[i].trim();
            if (candidate.isEmpty()) {
                continue;
            }
            byte[] bytes = parseIp(candidate);
            if (bytes == null) {
                log.warn("X-Forwarded-For 含非法地址 [{}]，忽略该头并回落 remoteAddr={}", candidate, remoteAddr);
                return null;
            }
            usableCount++;
            leftmostUsable = candidate;
            if (firstUntrustedFromRight == null && !isTrusted(bytes)) {
                firstUntrustedFromRight = candidate;
            }
        }
        if (firstUntrustedFromRight != null) {
            return firstUntrustedFromRight;
        }
        if (usableCount > 0) {
            // 全链（除空白槽位外）均为受信代理 → 最左侧地址
            return leftmostUsable;
        }
        return null;
    }

    /**
     * 校验单个转发头值（X-Real-IP / CF-Connecting-IP）：必须是合法 IP 字面量，
     * 否则记 warn 并返回 null（调用方回落 remoteAddr），不返回攻击者可控值。
     */
    private String trustedForwardedValue(String value, String remoteAddr) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String candidate = value.trim();
        if (parseIp(candidate) == null) {
            log.warn("转发头值非法 [{}]，回落 remoteAddr={}", candidate, remoteAddr);
            return null;
        }
        return candidate;
    }

    private boolean isTrusted(byte[] ipBytes) {
        for (TrustedProxy proxy : trustedProxies) {
            if (proxy.matches(ipBytes)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析受信代理配置：逗号分隔，支持 IPv4 CIDR 与 IPv6 精确地址。
     * 不支持的形式记 warn 并跳过，绝不抛异常阻断启动。
     */
    private List<TrustedProxy> parseTrustedProxies(String config) {
        List<TrustedProxy> proxies = new ArrayList<>();
        if (config == null || config.isBlank()) {
            return proxies;
        }
        for (String part : config.split(",")) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            TrustedProxy proxy = TrustedProxy.parse(entry);
            if (proxy == null) {
                log.warn("security.trusted-proxies 含不支持的受信代理形式 [{}]（仅支持 IPv4 CIDR 与 IPv6 精确地址），已跳过", entry);
            } else {
                proxies.add(proxy);
            }
        }
        return proxies;
    }

    /**
     * 解析 IP 字面量为字节数组（IPv4 4 字节 / IPv6 16 字节），非法返回 null。
     * 纯手工解析，绝不触发 DNS 查询（防止主机名形式的值被解析成攻击者可控地址）。
     */
    static byte[] parseIp(String value) {
        if (value == null) {
            return null;
        }
        String s = value.trim();
        if (s.isEmpty() || "unknown".equalsIgnoreCase(s)) {
            return null;
        }
        if (s.indexOf(':') >= 0) {
            return parseIpv6(s);
        }
        return parseIpv4(s);
    }

    private static byte[] parseIpv4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[IPV4_BYTES];
        for (int i = 0; i < IPV4_BYTES; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) {
                return null;
            }
            int value = 0;
            for (int j = 0; j < part.length(); j++) {
                char c = part.charAt(j);
                if (c < '0' || c > '9') {
                    return null;
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                return null;
            }
            out[i] = (byte) value;
        }
        return out;
    }

    /**
     * 解析 IPv6 字面量（支持 {@code ::} 压缩与末尾内嵌 IPv4，如 ::ffff:192.168.0.1）。
     * 内嵌 IPv4 以两个 0 组占位归一化，解析完成后回写实际 v4 字节。
     */
    private static byte[] parseIpv6(String s) {
        String body = s;
        byte[] embeddedV4 = null;
        int lastColon = s.lastIndexOf(':');
        if (s.indexOf('.') >= 0) {
            // 含点的 IPv6 只允许末尾内嵌 IPv4；纯 IPv4 不会有冒号
            if (lastColon < 0) {
                return null;
            }
            embeddedV4 = parseIpv4(s.substring(lastColon + 1));
            if (embeddedV4 == null) {
                return null;
            }
            body = s.substring(0, lastColon) + ":0:0";
        }
        List<Integer> groups = new ArrayList<>();
        int doubleColon = body.indexOf("::");
        if (doubleColon >= 0) {
            if (body.indexOf("::", doubleColon + 1) >= 0) {
                return null; // 至多一个 "::"
            }
            if (!parseIpv6Groups(body.substring(0, doubleColon), groups)) {
                return null;
            }
            int leftCount = groups.size();
            if (!parseIpv6Groups(body.substring(doubleColon + 2), groups)) {
                return null;
            }
            if (groups.size() > 8) {
                return null;
            }
            int fill = 8 - groups.size();
            for (int i = 0; i < fill; i++) {
                groups.add(leftCount, 0);
            }
        } else {
            if (!parseIpv6Groups(body, groups) || groups.size() != 8) {
                return null;
            }
        }
        byte[] out = new byte[IPV6_BYTES];
        for (int i = 0; i < 8; i++) {
            int group = groups.get(i);
            out[2 * i] = (byte) (group >> 8);
            out[2 * i + 1] = (byte) (group & 0xFF);
        }
        if (embeddedV4 != null) {
            out[12] = embeddedV4[0];
            out[13] = embeddedV4[1];
            out[14] = embeddedV4[2];
            out[15] = embeddedV4[3];
        }
        return out;
    }

    private static boolean parseIpv6Groups(String part, List<Integer> groups) {
        if (part.isEmpty()) {
            return true;
        }
        for (String piece : part.split(":", -1)) {
            if (piece.isEmpty() || piece.length() > 4) {
                return false;
            }
            int value = 0;
            for (int i = 0; i < piece.length(); i++) {
                char c = piece.charAt(i);
                int digit;
                if (c >= '0' && c <= '9') {
                    digit = c - '0';
                } else if (c >= 'a' && c <= 'f') {
                    digit = c - 'a' + 10;
                } else if (c >= 'A' && c <= 'F') {
                    digit = c - 'A' + 10;
                } else {
                    return false;
                }
                value = (value << 4) | digit;
            }
            groups.add(value);
        }
        return true;
    }

    /**
     * 单条受信代理：prefixLen = -1 表示精确匹配（IPv4/IPv6 皆可），
     * 否则为 IPv4 CIDR（仅 IPv4，IPv6 CIDR 按不支持处理）。
     */
    private static final class TrustedProxy {

        private final byte[] base;
        private final int prefixLen;

        private TrustedProxy(byte[] base, int prefixLen) {
            this.base = base;
            this.prefixLen = prefixLen;
        }

        static TrustedProxy parse(String entry) {
            int slash = entry.indexOf('/');
            if (slash < 0) {
                byte[] base = parseIp(entry);
                return base == null ? null : new TrustedProxy(base, -1);
            }
            byte[] base = parseIp(entry.substring(0, slash));
            if (base == null || base.length != IPV4_BYTES) {
                // IPv6 CIDR 与非法前缀均不支持，由调用方记 warn 并跳过
                return null;
            }
            int prefix;
            try {
                prefix = Integer.parseInt(entry.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (prefix < 0 || prefix > 32) {
                return null;
            }
            return new TrustedProxy(base, prefix);
        }

        boolean matches(byte[] candidate) {
            if (candidate.length != base.length) {
                return false;
            }
            if (prefixLen < 0) {
                return Arrays.equals(base, candidate);
            }
            int fullBytes = prefixLen / 8;
            int remBits = prefixLen % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (base[i] != candidate[i]) {
                    return false;
                }
            }
            if (remBits > 0) {
                int mask = (0xFF << (8 - remBits)) & 0xFF;
                if ((base[fullBytes] & mask) != (candidate[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }
}
