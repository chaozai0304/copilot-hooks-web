package com.copilot.hooks.security;

public record AppPrincipal(Long userId, String username, String role, Long tokenId) {
    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }
}
