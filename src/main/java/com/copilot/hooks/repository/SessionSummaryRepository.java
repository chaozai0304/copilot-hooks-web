package com.copilot.hooks.repository;

import com.copilot.hooks.domain.SessionSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SessionSummaryRepository extends JpaRepository<SessionSummary, Long> {
    Optional<SessionSummary> findBySessionDbId(Long sessionDbId);
    List<SessionSummary> findByUserIdOrderByUpdatedAtDesc(Long userId);
}
