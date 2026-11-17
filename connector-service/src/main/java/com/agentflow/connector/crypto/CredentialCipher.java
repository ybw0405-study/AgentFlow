package com.agentflow.connector.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 凭据加密（AES-256-GCM）。
 *
 * <p>密文格式：{@code 12B IV || ciphertext || 16B tag}，整体存 VARBINARY。
 * AAD 绑定 {@code connectorId|tenantId}，使密文无法被搬到别的连接器/租户下解密
 * （防「换个 connector_id 就读到别人的密钥」）。
 *
 * <p>主密钥来自 {@code AF_CRED_MASTER_KEY}（base64 32 字节）；keyId 为主密钥 SHA-256
 * 前 12 位十六进制，随密文入库，为将来轮换留下版本位。
 */
public final class CredentialCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;
    private final String keyId;

    public CredentialCipher(String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("AF_CRED_MASTER_KEY must be base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "AF_CRED_MASTER_KEY must decode to 32 bytes, got " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
        this.keyId = keyIdOf(raw);
    }

    public String keyId() {
        return keyId;
    }

    public static byte[] aad(String connectorId, String tenantId) {
        return (connectorId + "|" + tenantId).getBytes(StandardCharsets.UTF_8);
    }

    public byte[] seal(byte[] plaintext, byte[] aad) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            byte[] sealed = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
        } catch (Exception e) {
            throw new IllegalStateException("credential encryption failed", e);
        }
    }

    public byte[] open(byte[] stored, byte[] aad) {
        try {
            if (stored == null || stored.length <= IV_BYTES) {
                throw new IllegalArgumentException("credential ciphertext malformed");
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(stored, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            return cipher.doFinal(stored, IV_BYTES, stored.length - IV_BYTES);
        } catch (Exception e) {
            throw new IllegalStateException("credential decryption failed", e);
        }
    }

    /** 一次性签名（OAuth state 防伪）：HMAC-SHA256，密钥由主密钥派生。 */
    public byte[] sign(byte[] payload) {
        try {
            byte[] derived = MessageDigest.getInstance("SHA-256")
                    .digest(concat(key.getEncoded(), "af-oauth-state".getBytes(StandardCharsets.UTF_8)));
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(derived, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (Exception e) {
            throw new IllegalStateException("state signing failed", e);
        }
    }

    public boolean verify(byte[] payload, byte[] signature) {
        return MessageDigest.isEqual(sign(payload), signature);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String keyIdOf(byte[] raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
