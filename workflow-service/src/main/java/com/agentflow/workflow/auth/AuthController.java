package com.agentflow.workflow.auth;

import com.agentflow.common.api.ApiResponse;
import com.agentflow.common.security.JwtService;
import com.agentflow.workflow.security.JwtAuthFilter;
import com.agentflow.common.tenant.TenantHierarchyResolver;
import com.agentflow.workflow.tenant.MybatisTenantTreePort;
import com.agentflow.workflow.tenant.TenantMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 认证端点。刷新令牌经 HttpOnly Cookie（af_refresh，path 限定 /api/v1/auth）。 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public static final String REFRESH_COOKIE = "af_refresh";

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;
    private final UserMapper userMapper;
    private final TenantMapper tenantMapper;
    private final boolean cookieSecure;
    private final long refreshTtlDays;

    public AuthController(AuthService authService, RefreshTokenService refreshTokenService,
                          UserMapper userMapper, TenantMapper tenantMapper,
                          JwtService jwtService,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${agentflow.jwt.cookie-secure:true}") boolean cookieSecure,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${agentflow.jwt.refresh-ttl-days:14}") long refreshTtlDays) {
        this.authService = authService;
        this.refreshTokenService = refreshTokenService;
        this.userMapper = userMapper;
        this.tenantMapper = tenantMapper;
        this.cookieSecure = cookieSecure;
        this.refreshTtlDays = refreshTtlDays;
    }

    public record LoginRequest(@NotBlank @Size(max = 255) String email,
                               @NotBlank @Size(max = 72) String password) {
    }

    public record LoginData(String accessToken, String tokenType, long expiresIn) {
    }

    public record MembershipData(String tenantId, String name, String type, String role) {
    }

    public record MeData(String userId, String email, String displayName,
                         List<MembershipData> memberships) {
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginData>> login(
            @Validated @RequestBody LoginRequest body, HttpServletRequest request) {
        var result = authService.login(body.email(), body.password(),
                body.email() + "|" + request.getRemoteAddr());
        var refresh = refreshTokenService.startFamily(result.userId());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(refresh.rawToken()).toString())
                .body(ApiResponse.ok(
                        new LoginData(result.accessToken(), "Bearer", result.expiresIn())));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginData>> refresh(HttpServletRequest request) {
        String raw = readRefreshCookie(request);
        if (raw == null || raw.isBlank()) {
            // 无 refresh Cookie 属认证失败，返回 401；不能透传给 rotate() 触发 NPE 500
            throw new com.agentflow.common.error.BusinessException(
                    com.agentflow.common.error.ErrorCode.USR_TOKEN_001,
                    "refresh token missing");
        }
        var rotated = refreshTokenService.rotate(raw);  // 非法/重用在此抛出
        var user = userMapper.findById(rotated.userId());
        if (user == null || !"ACTIVE".equals(user.status())) {
            throw new com.agentflow.common.error.BusinessException(
                    com.agentflow.common.error.ErrorCode.USR_TOKEN_001, "account unavailable");
        }
        var login = authService.issueFor(user);  // 不走登录限流
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(rotated.rawToken()).toString())
                .body(ApiResponse.ok(
                        new LoginData(login.accessToken(), "Bearer", login.expiresIn())));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        String raw = readRefreshCookie(request);
        if (raw != null) {
            refreshTokenService.revoke(raw);  // 静默：已失效也不报错
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearCookie().toString())
                .build();
    }

    @GetMapping("/me")
    public ApiResponse<MeData> me(HttpServletRequest request) {
        String userId = (String) request.getAttribute(JwtAuthFilter.ATTR_USER_ID);
        var user = userMapper.findById(userId);
        if (user == null) {
            throw new com.agentflow.common.error.BusinessException(
                    com.agentflow.common.error.ErrorCode.USR_TOKEN_001, "account unavailable");
        }
        return ApiResponse.ok(new MeData(user.id(), user.email(), user.displayName(),
                effectiveSpaces(userId)));
    }

    /**
     * 可进入的空间（含三级继承）：直接成员关系向下展开到子树里的所有 SPACE，
     * 角色按 {@link TenantHierarchyResolver} 解析；平台 ADMIN 额外获得全部空间。
     */
    private List<MembershipData> effectiveSpaces(String userId) {
        TenantHierarchyResolver resolver =
                new TenantHierarchyResolver(new MybatisTenantTreePort(tenantMapper));
        Map<String, MembershipData> bySpace = new LinkedHashMap<>();
        if ("ADMIN".equals(tenantMapper.findPlatformRole(userId))) {
            for (var space : tenantMapper.listSpaces()) {
                bySpace.put(space.id(), new MembershipData(space.id(), space.name(),
                        "SPACE", "ADMIN"));
            }
        }
        for (var membership : tenantMapper.listMemberships(userId)) {
            List<TenantMapper.MembershipRow> spaces = "SPACE".equals(membership.type())
                    ? List.of(membership)
                    : tenantMapper.listDescendantSpaces(membership.id());
            for (var space : spaces) {
                var resolved = resolver.resolve(space.id(), userId);
                if (resolved.isEmpty()) {
                    continue;
                }
                String role = resolved.get().role();
                MembershipData existing = bySpace.get(space.id());
                if (existing == null || TenantHierarchyResolver.rank(role)
                        > TenantHierarchyResolver.rank(existing.role())) {
                    bySpace.put(space.id(), new MembershipData(space.id(), space.name(),
                            "SPACE", role));
                }
            }
        }
        return List.copyOf(bySpace.values());
    }

    private ResponseCookie refreshCookie(String raw) {
        return ResponseCookie.from(REFRESH_COOKIE, raw)
                .httpOnly(true).secure(cookieSecure).sameSite("Lax")
                .path("/api/v1/auth").maxAge(refreshTtlDays * 24 * 3600)
                .build();
    }

    private ResponseCookie clearCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true).secure(cookieSecure).sameSite("Lax")
                .path("/api/v1/auth").maxAge(0)
                .build();
    }

    private static String readRefreshCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (var c : request.getCookies()) {
            if (REFRESH_COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
