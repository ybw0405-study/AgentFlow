package com.agentflow.connector.service;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

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
 * <p>DNS rebinding 防护：{@link #validate} 返回时携带已解析并校验过的 IP 快照，
 * 调用方在真正出网前用 {@link #assertNoRebinding} 再次解析并比对——若解析结果漂移、
 * 或出现被封禁地址则拒绝连接，缩小「校验—连接」之间的 TOCTOU 窗口。仍建议生产环境
 * 配合固定 egress IP 的出网代理做最终兜底（防连接阶段的二次 rebinding）。
 */
public final class SandboxPolicy {

    /** 校验结果：通过校验的 URI + 当时的 DNS 解析快照（用于连接前防 rebinding 复核）。 */
    public record ValidatedTarget(URI uri, List<InetAddress> resolved) {
    }

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

    /** 返回 URI 的兼容入口（不含 DNS 快照）。 */
    public URI validate(String rawUrl, List<String> allowedHosts) {
        return validateWithDns(rawUrl, allowedHosts).uri();
    }

    /** 校验 URL 并携带通过校验时的 DNS 解析快照。 */
    public ValidatedTarget validateWithDns(String rawUrl, List<String> allowedHosts) {
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
        InetAddress[] addresses = resolveHost(host);
        for (InetAddress address : addresses) {
            if (isBlocked(address) && !(allowLoopback && address.isLoopbackAddress())) {
                throw new Violation("destination address blocked: " + address.getHostAddress());
            }
        }
        return new ValidatedTarget(uri, List.of(addresses));
    }

    /**
     * 连接前复核：再次解析同一 host，若出现被封禁地址、或解析集合与校验时漂移
     * （DNS rebinding 特征）则拒绝。缩小校验与出网之间的 TOCTOU 窗口。
     *
     * @param host     目标主机名（来自已校验 URI）
     * @param approved {@link #validateWithDns}  captured 的地址快照
     */
    public void assertNoRebinding(String host, List<InetAddress> approved) {
        InetAddress[] current = resolveHost(host);
        Set<String> approvedIps = approved.stream()
                .map(InetAddress::getHostAddress).collect(Collectors.toSet());
        for (InetAddress address : current) {
            if (isBlocked(address) && !(allowLoopback && address.isLoopbackAddress())) {
                throw new Violation("dns rebound to blocked address: " + address.getHostAddress());
            }
            if (!approvedIps.contains(address.getHostAddress())) {
                throw new Violation("dns answer drifted for " + host
                        + ": " + address.getHostAddress());
            }
        }
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
