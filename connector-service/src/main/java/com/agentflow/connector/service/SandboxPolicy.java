package com.agentflow.connector.service;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * 出网沙箱：域名白名单 + 私网地址拒绝。
 *
 * <p>规则（架构 §5.2「沙箱」）：
 * <ul>
 *   <li>scheme 只允许 https；本地回环 + 显式 allowInsecureHttp 时才允许 http（仅测试/自建内网网关）；</li>
 *   <li>host 必须命中 {@code allowedHosts}（支持 {@code *.example.com} 通配，仅匹配一层以上子域）；</li>
 *   <li>DNS 解析结果不得为环回/私网/链路本地/组播/保留地址 —— 挡住 {@code http://169.254.169.254}
 *       这类云元数据 SSRF；</li>
 *   <li>默认禁止环回（allowLoopback 仅供单测指向本地 stub server）。</li>
 * </ul>
 *
 * <p>残余风险：DNS rebinding（解析时合法、连接时被改指内网）。缓解是白名单强约束 +
 * 生产环境出网代理固定 egress IP；此处不做 IP 钉扎，避免破坏 CDN。
 */
public final class SandboxPolicy {

    public static final class Violation extends RuntimeException {
        public Violation(String message) {
            super(message);
        }
    }

    /** 域名解析（可注入，便于单测在不依赖 DNS 的前提下验证私网拦截）。 */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final boolean allowInsecureHttp;
    private final boolean allowLoopback;
    private final HostResolver resolver;

    public SandboxPolicy(boolean allowInsecureHttp, boolean allowLoopback) {
        this(allowInsecureHttp, allowLoopback, InetAddress::getAllByName);
    }

    public SandboxPolicy(boolean allowInsecureHttp, boolean allowLoopback,
                         HostResolver resolver) {
        this.allowInsecureHttp = allowInsecureHttp;
        this.allowLoopback = allowLoopback;
        this.resolver = resolver;
    }

    public URI validate(String rawUrl, List<String> allowedHosts) {
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException e) {
            throw new Violation("invalid url");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new Violation("url has no host");
        }
        if ("http".equals(scheme)) {
            if (!allowInsecureHttp || !isLoopbackLiteral(host)) {
                throw new Violation("http is not allowed");
            }
        } else if (!"https".equals(scheme)) {
            throw new Violation("unsupported scheme: " + scheme);
        }
        if (!hostAllowed(host, allowedHosts)) {
            throw new Violation("host not in allowlist: " + host);
        }
        for (InetAddress address : resolveHost(host)) {
            if (isBlocked(address) && !(allowLoopback && address.isLoopbackAddress())) {
                throw new Violation("destination address blocked: " + address.getHostAddress());
            }
        }
        return uri;
    }

    public static boolean hostAllowed(String host, List<String> allowedHosts) {
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            return false;
        }
        String candidate = host.toLowerCase(Locale.ROOT);
        for (String rule : allowedHosts) {
            if (rule == null || rule.isBlank()) {
                continue;
            }
            String normalized = rule.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("*.")) {
                String suffix = normalized.substring(1);           // ".example.com"
                if (candidate.endsWith(suffix) && candidate.length() > suffix.length()) {
                    return true;
                }
            } else if (normalized.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (address instanceof Inet6Address) {
            int first = raw[0] & 0xff;
            // fc00::/7 唯一本地地址；::ffff:0:0/96 已在 IPv4 映射时由上面判定覆盖
            return (first & 0xfe) == 0xfc;
        }
        int b0 = raw[0] & 0xff;
        int b1 = raw[1] & 0xff;
        return b0 == 0 || (b0 == 100 && b1 >= 64 && b1 <= 127);  // 0.0.0.0/8 + CGNAT 100.64/10
    }

    private InetAddress[] resolveHost(String host) {
        try {
            return resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new Violation("cannot resolve host: " + host);
        }
    }

    private static boolean isLoopbackLiteral(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host);
    }
}
