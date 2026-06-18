package com.copilot.hooks.repository;

import com.copilot.hooks.domain.ApiToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiTokenRepository extends JpaRepository<ApiToken, Long> {
    Optional<ApiToken> findByTokenHash(String tokenHash);
    List<ApiToken> findByUserIdOrderByCreatedAtDesc(Long userId);
}
