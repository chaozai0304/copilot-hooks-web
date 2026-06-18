package com.copilot.hooks.security;

import com.copilot.hooks.domain.User;
import com.copilot.hooks.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CurrentUser {

    private final UserRepository userRepo;

    public AppPrincipal require() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || "anonymousUser".equals(a.getPrincipal())) {
            throw new AccessDeniedException("not authenticated");
        }
        Object p = a.getPrincipal();
        if (p instanceof AppPrincipal ap) return ap;
        if (p instanceof UserDetails ud) {
            User u = userRepo.findByUsername(ud.getUsername())
                    .orElseThrow(() -> new AccessDeniedException("unknown user"));
            return new AppPrincipal(u.getId(), u.getUsername(), u.getRole(), null);
        }
        if (p instanceof String s && !"anonymousUser".equals(s)) {
            User u = userRepo.findByUsername(s)
                    .orElseThrow(() -> new AccessDeniedException("unknown user"));
            return new AppPrincipal(u.getId(), u.getUsername(), u.getRole(), null);
        }
        throw new AccessDeniedException("unsupported principal");
    }

    public Long requireId() { return require().userId(); }
}
