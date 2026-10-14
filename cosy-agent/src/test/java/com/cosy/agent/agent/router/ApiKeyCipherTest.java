package com.cosy.agent.agent.router;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * api-key 加解密（AES/GCM）单测：round-trip 一致、空值原样、旧明文兼容。
 */
class ApiKeyCipherTest {

    private final ApiKeyCipher cipher = new ApiKeyCipher();

    @Test
    void encryptDecrypt_roundTrip_preservesValue() {
        String plain = "sk-zhipu-abcdef1234567890";
        String stored = cipher.encrypt(plain);
        // 密文非明文
        assertThat(stored).isNotEqualTo(plain);
        assertThat(stored).doesNotContain(plain);
        assertThat(cipher.decrypt(stored)).isEqualTo(plain);
    }

    @Test
    void encrypt_decrypt_randomIv_differentCiphertext() {
        String plain = "sk-same-value";
        assertThat(cipher.encrypt(plain)).isNotEqualTo(cipher.encrypt(plain));
    }

    @Test
    void blankOrNull_passesThrough() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.encrypt("")).isEmpty();
        assertThat(cipher.decrypt(null)).isNull();
        assertThat(cipher.decrypt("")).isEmpty();
    }

    @Test
    void decrypt_legacyPlaintext_returnsAsIs() {
        // 旧版本明文落库的数据：解密失败按原样返回（向后兼容）
        assertThat(cipher.decrypt("sk-legacy-plain-key")).isEqualTo("sk-legacy-plain-key");
    }
}
