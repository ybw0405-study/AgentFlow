package com.agentflow.connector.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 沙箱：白名单、私网/元数据地址、协议限制。 */
class SandboxPolicyTest {

    private static final List<String> HOSTS = List.of("api.example.com", "*.trusted.io");

    /** 固定解析到公网地址，避免单测依赖真实 DNS。 */
    private static SandboxPolicy policy(boolean allowHttp, boolean allowLoopback) {
        return new SandboxPolicy(allowHttp, allowLoopback,
                host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")});
    }

    @Test
    void allowsWhitelistedHost() {
        assertThat(policy(false, false).validate("https://api.example.com/v1/x", HOSTS).getHost())
                .isEqualTo("api.example.com");
    }

    @Test
    void wildcardMatchesSubdomainOnly() {
        assertThat(SandboxPolicy.hostAllowed("a.trusted.io", HOSTS)).isTrue();
        assertThat(SandboxPolicy.hostAllowed("a.b.trusted.io", HOSTS)).isTrue();
        assertThat(SandboxPolicy.hostAllowed("trusted.io", HOSTS)).isFalse();
        assertThat(SandboxPolicy.hostAllowed("evil-trusted.io", HOSTS)).isFalse();
    }

    @Test
    void rejectsUnlistedHost() {
        assertThatThrownBy(() -> policy(false, false).validate("https://evil.com/x", HOSTS))
                .isInstanceOf(SandboxPolicy.Violation.class);
    }

    @Test
    void rejectsInsecureHttpByDefault() {
        assertThatThrownBy(() -> policy(false, true).validate("http://127.0.0.1:9000/x", HOSTS))
                .isInstanceOf(SandboxPolicy.Violation.class);
    }

    @Test
    void rejectsMetadataAddress() throws Exception {
        assertThat(SandboxPolicy.isBlocked(InetAddress.getByName("169.254.169.254"))).isTrue();
        assertThat(SandboxPolicy.isBlocked(InetAddress.getByName("10.0.0.5"))).isTrue();
        assertThat(SandboxPolicy.isBlocked(InetAddress.getByName("192.168.1.1"))).isTrue();
        assertThat(SandboxPolicy.isBlocked(InetAddress.getByName("127.0.0.1"))).isTrue();
        assertThat(SandboxPolicy.isBlocked(InetAddress.getByName("8.8.8.8"))).isFalse();
    }

    @Test
    void blocksLoopbackUnlessAllowed() {
        var blocked = new SandboxPolicy(true, false,
                host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        assertThatThrownBy(() -> blocked.validate("http://127.0.0.1:9000/x", List.of("127.0.0.1")))
                .isInstanceOf(SandboxPolicy.Violation.class);
        var allowed = new SandboxPolicy(true, true,
                host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        assertThat(allowed.validate("http://127.0.0.1:9000/x", List.of("127.0.0.1")).getPort())
                .isEqualTo(9000);
    }

    @Test
    void rejectsNonHttpSchemesAndMissingHost() {
        assertThatThrownBy(() -> policy(false, false).validate("file:///etc/passwd", HOSTS))
                .isInstanceOf(SandboxPolicy.Violation.class);
        assertThatThrownBy(() -> policy(false, false).validate("https:///nohost", HOSTS))
                .isInstanceOf(SandboxPolicy.Violation.class);
    }
}
