package com.copilot.hooks.config;

import com.copilot.hooks.security.BearerTokenAuthFilter;
import com.copilot.hooks.security.TokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final BearerTokenAuthFilter bearerTokenAuthFilter;
    private final TokenService tokenService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(b -> {})
            .authorizeHttpRequests(reg -> reg
                .requestMatchers(
                        "/", "/index.html", "/ui/**",
                        "/static/**", "/assets/**",
                        "/*.css", "/*.js", "/*.ico", "/*.svg", "/*.png", "/*.map",
                        "/favicon.ico", "/error",
                        "/actuator/health", "/api/health"
                ).permitAll()
                .requestMatchers("/api/hooks/ingest").authenticated()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/mcp/**").authenticated()
                .anyRequest().authenticated()
            )
            .addFilterBefore(bearerTokenAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapAdmin() {
        tokenService.bootstrapAdmin();
    }
}
