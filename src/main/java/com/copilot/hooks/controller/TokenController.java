package com.copilot.hooks.controller;

import com.copilot.hooks.domain.ApiToken;
import com.copilot.hooks.security.AppPrincipal;
import com.copilot.hooks.security.CurrentUser;
import com.copilot.hooks.security.TokenService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tokens")
@RequiredArgsConstructor
public class TokenController {

    private final TokenService tokenService;
    private final CurrentUser currentUser;

    public record CreateRequest(@NotBlank String name, OffsetDateTime expiresAt) {}

    @GetMapping
    public List<Map<String, Object>> list() {
        AppPrincipal me = currentUser.require();
        return tokenService.listForUser(me.userId()).stream().map(this::toView).toList();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest req) {
        AppPrincipal me = currentUser.require();
        TokenService.CreatedToken t = tokenService.createToken(me.userId(), req.name(), req.expiresAt());
        Map<String, Object> body = new HashMap<>();
        body.put("token", t.plaintext()); // Only returned on creation. Store it now.
        body.put("id", t.token().getId());
        body.put("name", t.token().getName());
        body.put("prefix", t.token().getTokenPrefix());
        body.put("expiresAt", t.token().getExpiresAt());
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> revoke(@PathVariable Long id) {
        AppPrincipal me = currentUser.require();
        tokenService.revoke(id, me.userId());
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> toView(ApiToken t) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("prefix", t.getTokenPrefix());
        m.put("expiresAt", t.getExpiresAt());
        m.put("lastUsedAt", t.getLastUsedAt());
        m.put("revoked", t.isRevoked());
        m.put("createdAt", t.getCreatedAt());
        return m;
    }
}
