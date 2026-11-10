package com.mindbase.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 管理员清库密钥校验器。
 *
 * <p>密钥必须来自外部配置（{@code admin.clear-all-key}），本类不持有任何默认密钥：
 * 配置为空时 {@link #matches} 直接返回 false，由调用方拒绝执行，绝不回退到内置密钥。
 * 比较使用 {@link MessageDigest#isEqual} 常量时间实现，避免字符串逐字符比较带来的时序侧信道。
 */
public final class AdminKeyVerifier {

    private AdminKeyVerifier() {
    }

    /**
     * 比较配置的清库密钥与请求提供的密钥。
     *
     * @param configured 服务端配置的密钥，可能为 null 或空白（未配置）
     * @param provided   请求方提供的密钥，可能为 null 或空白
     * @return 两者均非空白且常量时间比较一致时返回 true；任一空白或比较不一致返回 false
     */
    public static boolean matches(String configured, String provided) {
        if (isBlank(configured) || isBlank(provided)) {
            return false;
        }
        byte[] expected = configured.getBytes(StandardCharsets.UTF_8);
        byte[] actual = provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
