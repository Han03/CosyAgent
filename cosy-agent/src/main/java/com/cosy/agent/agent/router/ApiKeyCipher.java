package com.cosy.agent.agent.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 模型平台 api-key 加解密（AES/CBC/PKCS5Padding，全 JDK 兼容）。
 *
 * <p>用途：配置落库（MySQL/PG）前加密、读取后解密，接口/日志/目录只出掩码。
 * 密钥来源 env {@code COSY_AGENT_MODEL_KEY_SECRET}（16/24/32 字节）；未配置时
 * 使用内置开发密钥并告警（仅限本地演示，生产必须注入）。</p>
 *
 * <p>密文格式：{@code base64(iv 16B + ciphertext)}，每次加密随机 IV。</p>
 */
@Component
public class ApiKeyCipher {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyCipher.class);

    private static final String ALGO = "AES/CBC/PKCS5Padding";
    private static final int IV_LEN = 16;
    private static final int KEY_LEN = 16; // AES-128：全 JDK 支持，不依赖 JCE unlimited policy
    private static final String DEV_KEY = "cosy-dev-model-key-0123456789abcdef"; // 仅本地演示

    private final SecretKeySpec key;

    public ApiKeyCipher() {
        String secret = System.getenv("COSY_AGENT_MODEL_KEY_SECRET");
        if (secret == null || secret.isBlank()) {
            log.warn("未配置 COSY_AGENT_MODEL_KEY_SECRET，使用内置开发密钥（仅限本地演示，生产必须注入）");
            secret = DEV_KEY;
        }
        // SHA-256 派生固定 128 位密钥：任意长度 env 值均可，且不触发 JCE 密钥长度限制
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        byte[] raw = new byte[KEY_LEN];
        System.arraycopy(digest, 0, raw, 0, KEY_LEN);
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** 加密为 base64(iv+ciphertext)；空值原样返回（未配置 key 的平台） */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("api-key 加密失败", e);
        }
    }

    /** 解密；空值或非密文（旧明文数据）原样返回，保证向后兼容 */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) {
            return stored;
        }
        try {
            byte[] data = Base64.getDecoder().decode(stored);
            if (data.length <= IV_LEN) {
                return stored; // 长度异常视为旧明文
            }
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(data, 0, IV_LEN));
            byte[] plain = cipher.doFinal(data, IV_LEN, data.length - IV_LEN);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 旧版本明文（未加密数据）：解密失败原样返回，避免破坏既有配置
            log.warn("api-key 解密失败（按旧明文兼容处理，请重新保存平台配置以启用加密）");
            return stored;
        }
    }
}
