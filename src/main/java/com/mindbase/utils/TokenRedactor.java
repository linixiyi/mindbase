package com.mindbase.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 敏感令牌日志脱敏工具（task-0016）。
 *
 * <p>把完整 token 换成不可逆指纹后再写日志，避免 JWT 泄露即可重放：
 * 指纹格式为 {@code sha256:<SHA-256 前 8 位十六进制>…len=<字符长度>}，
 * 例如 {@code sha256:3a7bd3e2…len=189}；null 或空白输入返回 {@code empty}。</p>
 */
public final class TokenRedactor {

    /** null/空白输入的指纹占位。 */
    static final String EMPTY = "empty";

    private static final String PREFIX = "sha256:";
    private static final String LENGTH_SEP = "…len=";
    private static final int FINGERPRINT_HEX_BYTES = 4; // 前 4 字节 → 8 位十六进制
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private TokenRedactor() {
    }

    /**
     * 计算 token 的日志安全指纹：不可逆、可关联（同一 token 指纹一致）但不还原原文。
     *
     * @param token 原始 token（可为 null 或空白）
     * @return {@code empty} 或 {@code sha256:xxxxxxxx…len=N}
     */
    public static String fingerprint(String token) {
        if (token == null || token.isBlank()) {
            return EMPTY;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(PREFIX.length() + 8 + LENGTH_SEP.length() + 10);
            sb.append(PREFIX);
            for (int i = 0; i < FINGERPRINT_HEX_BYTES && i < hash.length; i++) {
                sb.append(HEX[(hash[i] >> 4) & 0x0f]).append(HEX[hash[i] & 0x0f]);
            }
            return sb.append(LENGTH_SEP).append(token.length()).toString();
        } catch (NoSuchAlgorithmException e) {
            // JVM 规范保证 SHA-256 一定存在；这里宁可抛错也不回退输出原文
            throw new IllegalStateException("JVM does not provide SHA-256", e);
        }
    }
}
