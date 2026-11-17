package com.agentflow.common.security;

import java.util.List;

/** 已认证主体（来自 JWT）。 */
public record AuthenticatedUser(String userId, List<String> roles) {

    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }

    public boolean isAdmin() {
        return hasRole("ADMIN");
    }
}
