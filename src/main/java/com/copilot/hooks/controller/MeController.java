package com.copilot.hooks.controller;

import com.copilot.hooks.domain.User;
import com.copilot.hooks.repository.UserRepository;
import com.copilot.hooks.security.AppPrincipal;
import com.copilot.hooks.security.CurrentUser;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController {

    private final CurrentUser currentUser;
    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;

    public record UpdateMeRequest(String displayName,
                                  String email,
                                  String currentPassword,
                                  @Size(max = 200) String newPassword) {}

    @GetMapping
    public Map<String, Object> me(@AuthenticationPrincipal AppPrincipal me) {
        Map<String, Object> out = new HashMap<>();
        if (me != null) {
            User user = userRepo.findById(me.userId()).orElse(null);
            out.put("authType", "bearer");
            out.put("userId", me.userId());
            out.put("username", me.username());
            out.put("role", me.role());
            out.put("tokenId", me.tokenId());
            out.put("displayName", user == null ? null : user.getDisplayName());
            out.put("email", user == null ? null : user.getEmail());
            return out;
        }
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.isAuthenticated() && !"anonymousUser".equals(a.getPrincipal())) {
            AppPrincipal principal = currentUser.require();
            User user = userRepo.findById(principal.userId()).orElse(null);
            out.put("authType", "basic");
            out.put("userId", principal.userId());
            out.put("username", principal.username());
            out.put("role", principal.role());
            out.put("tokenId", principal.tokenId());
            out.put("authorities", a.getAuthorities());
            out.put("displayName", user == null ? null : user.getDisplayName());
            out.put("email", user == null ? null : user.getEmail());
        }
        return out;
    }

    @PutMapping
    public Map<String, Object> updateMe(@RequestBody UpdateMeRequest req) {
        AppPrincipal me = currentUser.require();
        User user = userRepo.findById(me.userId()).orElseThrow();
        user.setDisplayName(blankToNull(req.displayName()));
        user.setEmail(blankToNull(req.email()));

        if (req.newPassword() != null && !req.newPassword().isBlank()) {
            if (req.currentPassword() == null || req.currentPassword().isBlank()) {
                throw new ResponseStatusException(BAD_REQUEST, "修改密码时必须填写当前密码");
            }
            if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
                throw new ResponseStatusException(BAD_REQUEST, "当前密码不正确");
            }
            user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        }

        userRepo.save(user);
        Map<String, Object> out = new HashMap<>();
        out.put("authType", me.tokenId() == null ? "basic" : "bearer");
        out.put("userId", user.getId());
        out.put("username", user.getUsername());
        out.put("role", user.getRole());
        out.put("tokenId", me.tokenId());
        out.put("displayName", user.getDisplayName());
        out.put("email", user.getEmail());
        return out;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
