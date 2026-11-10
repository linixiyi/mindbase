package com.mindbase.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 清库密钥校验器单测：覆盖 null/空白/相等/不等，
 * 核心保证是“任一侧空白即拒绝”，绝不回退到默认密钥。
 */
class AdminKeyVerifierTest {

    @Test
    void nullConfiguredIsRejected() {
        assertFalse(AdminKeyVerifier.matches(null, "any-provided-key"));
    }

    @Test
    void nullProvidedIsRejected() {
        assertFalse(AdminKeyVerifier.matches("configured-key", null));
    }

    @Test
    void bothNullIsRejected() {
        assertFalse(AdminKeyVerifier.matches(null, null));
    }

    @Test
    void emptyConfiguredIsRejected() {
        assertFalse(AdminKeyVerifier.matches("", "any-provided-key"));
    }

    @Test
    void whitespaceConfiguredIsRejected() {
        assertFalse(AdminKeyVerifier.matches("   ", "any-provided-key"));
    }

    @Test
    void emptyProvidedIsRejected() {
        assertFalse(AdminKeyVerifier.matches("configured-key", ""));
    }

    @Test
    void whitespaceProvidedIsRejected() {
        assertFalse(AdminKeyVerifier.matches("configured-key", "  "));
    }

    @Test
    void bothBlankIsRejected() {
        // 双侧空白不能因为“相等”而放行
        assertFalse(AdminKeyVerifier.matches("", ""));
        assertFalse(AdminKeyVerifier.matches(null, ""));
        assertFalse(AdminKeyVerifier.matches("  ", null));
    }

    @Test
    void equalKeysMatch() {
        assertTrue(AdminKeyVerifier.matches("s3cret-key", "s3cret-key"));
    }

    @Test
    void differentKeysDoNotMatch() {
        assertFalse(AdminKeyVerifier.matches("s3cret-key", "wrong-key"));
    }

    @Test
    void prefixOfConfiguredKeyDoesNotMatch() {
        // 前缀/超集密钥必须拒绝
        assertFalse(AdminKeyVerifier.matches("s3cret-key", "s3cret"));
        assertFalse(AdminKeyVerifier.matches("s3cret", "s3cret-key"));
    }

    @Test
    void caseDifferenceDoesNotMatch() {
        assertFalse(AdminKeyVerifier.matches("S3cret-Key", "s3cret-key"));
    }

    @Test
    void keysWithSpecialCharactersMatch() {
        assertTrue(AdminKeyVerifier.matches("k&=+/?#密", "k&=+/?#密"));
    }
}
