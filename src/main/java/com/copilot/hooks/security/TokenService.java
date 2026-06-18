package com.copilot.hooks.security;

import com.copilot.hooks.domain.ApiToken;
import com.copilot.hooks.domain.User;
import com.copilot.hooks.repository.ApiTokenRepository;
import com.copilot.hooks.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TokenService {

    private static final String PREFIX = "chk_";
    private static final SecureRandom RNG = new SecureRandom();

    private final ApiTokenRepository tokenRepo;
    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.bootstrap-username:admin}")
    private String bootstrapAdmin;

    @Value("${app.admin.bootstrap-password:admin123}")
    private String bootstrapPassword;

    @Transactional
    public void bootstrapAdmin() {
        if (userRepo.existsByUsername(bootstrapAdmin)) return;
        User u = new User();
        u.setUsername(bootstrapAdmin);
        u.setDisplayName("Administrator");
        u.setRole("ADMIN");
        u.setEnabled(true);
        u.setPasswordHash(passwordEncoder.encode(bootstrapPassword));
        userRepo.save(u);
    }

    public record CreatedToken(ApiToken token, String plaintext) {}

    @Transactional
    public CreatedToken createToken(Long userId, String name, OffsetDateTime expiresAt) {
        byte[] buf = new byte[32];
        RNG.nextBytes(buf);
        String secret = HexFormat.of().formatHex(buf);
        String plaintext = PREFIX + secret;

        ApiToken t = new ApiToken();
        t.setUserId(userId);
        t.setName(name == null || name.isBlank() ? "default" : name);
        t.setTokenHash(sha256(plaintext));
        t.setTokenPrefix(plaintext.substring(0, Math.min(plaintext.length(), 12)));
        t.setExpiresAt(expiresAt);
        return new CreatedToken(tokenRepo.save(t), plaintext);
    }

    @Transactional
    public Optional<ApiToken> resolve(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return Optional.empty();
        String hash = sha256(plaintext);
        Optional<ApiToken> found = tokenRepo.findByTokenHash(hash);
        found.filter(ApiToken::isActive).ifPresent(t -> {
            t.setLastUsedAt(OffsetDateTime.now());
        });
        return found.filter(ApiToken::isActive);
    }

    @Transactional
    public void revoke(Long tokenId, Long userId) {
        tokenRepo.findById(tokenId).ifPresent(t -> {
            if (!t.getUserId().equals(userId)) return;
            t.setRevoked(true);
        });
    }

    public List<ApiToken> listForUser(Long userId) {
        return tokenRepo.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
