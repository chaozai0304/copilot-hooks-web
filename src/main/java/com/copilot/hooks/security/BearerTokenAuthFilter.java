package com.copilot.hooks.security;

import com.copilot.hooks.domain.ApiToken;
import com.copilot.hooks.domain.User;
import com.copilot.hooks.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class BearerTokenAuthFilter extends OncePerRequestFilter {

    private final TokenService tokenService;
    private final UserRepository userRepo;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String header = req.getHeader("Authorization");
        String token = null;
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7).trim();
        } else if (req.getHeader("X-Copilot-Token") != null) {
            token = req.getHeader("X-Copilot-Token").trim();
        }

        if (token != null && !token.isBlank()) {
            Optional<ApiToken> apiToken = tokenService.resolve(token);
            if (apiToken.isPresent()) {
                Optional<User> user = userRepo.findById(apiToken.get().getUserId());
                if (user.isPresent() && user.get().isEnabled()) {
                    User u = user.get();
                    AppPrincipal principal = new AppPrincipal(u.getId(), u.getUsername(), u.getRole(), apiToken.get().getId());
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole())));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        }
        chain.doFilter(req, res);
    }
}
