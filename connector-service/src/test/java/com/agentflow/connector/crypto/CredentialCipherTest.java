package com.agentflow.connector.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 凭据加密：往返、AAD 绑定、篡改检测、state 签名。 */
class CredentialCipherTest {

    private static final String KEY = "ZGV2LW9ubHktY3JlZC1tYXN0ZXIta2V5LTMyYnl0ZXM=";

    private final CredentialCipher cipher = new CredentialCipher(KEY);

    @Test
    void roundTrip() {
        byte[] aad = CredentialCipher.aad("conn-1", "space-a");
        byte[] sealed = cipher.seal("s3cr3t".getBytes(StandardCharsets.UTF_8), aad);
        assertThat(new String(cipher.open(sealed, aad), StandardCharsets.UTF_8))
                .isEqualTo("s3cr3t");
    }

    @Test
    void ciphertextIsNotPlaintextAndIvIsRandom() {
        byte[] aad = CredentialCipher.aad("conn-1", "space-a");
        byte[] first = cipher.seal("same".getBytes(StandardCharsets.UTF_8), aad);
        byte[] second = cipher.seal("same".getBytes(StandardCharsets.UTF_8), aad);
        assertThat(new String(first, StandardCharsets.UTF_8)).doesNotContain("same");
        assertThat(first).isNotEqualTo(second);   // 每次随机 IV
    }

    @Test
    void aadBindsCiphertextToConnectorAndTenant() {
        byte[] sealed = cipher.seal("token".getBytes(StandardCharsets.UTF_8),
                CredentialCipher.aad("conn-1", "space-a"));
        assertThatThrownBy(() -> cipher.open(sealed, CredentialCipher.aad("conn-2", "space-a")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.open(sealed, CredentialCipher.aad("conn-1", "space-b")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperedCiphertextFails() {
        byte[] aad = CredentialCipher.aad("conn-1", "space-a");
        byte[] sealed = cipher.seal("token".getBytes(StandardCharsets.UTF_8), aad);
        sealed[sealed.length - 1] ^= 0x01;
        assertThatThrownBy(() -> cipher.open(sealed, aad))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsWrongKeyLength() {
        assertThatThrownBy(() -> new CredentialCipher("dG9vLXNob3J0"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CredentialCipher("not-base64!"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void signatureVerifiesPayloadOnly() {
        byte[] payload = "conn-1|space-a|1700000000".getBytes(StandardCharsets.UTF_8);
        byte[] signature = cipher.sign(payload);
        assertThat(cipher.verify(payload, signature)).isTrue();
        assertThat(cipher.verify("conn-1|space-b|1700000000".getBytes(StandardCharsets.UTF_8),
                signature)).isFalse();
    }
}
