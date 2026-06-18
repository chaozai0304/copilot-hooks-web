package com.copilot.hooks.repository;

import com.copilot.hooks.domain.HookSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface HookSessionRepository extends JpaRepository<HookSession, Long> {
    Optional<HookSession> findByUserIdAndSessionId(Long userId, String sessionId);
    Page<HookSession> findAllByOrderByLastEventAtDesc(Pageable pageable);
    Page<HookSession> findByUserIdOrderByLastEventAtDesc(Long userId, Pageable pageable);
    Optional<HookSession> findByIdAndUserId(Long id, Long userId);

        Page<HookSession> findAllByLastEventAtGreaterThanEqualOrderByLastEventAtDesc(OffsetDateTime startAt, Pageable pageable);
        Page<HookSession> findAllByLastEventAtLessThanOrderByLastEventAtDesc(OffsetDateTime endAt, Pageable pageable);
        Page<HookSession> findAllByLastEventAtGreaterThanEqualAndLastEventAtLessThanOrderByLastEventAtDesc(OffsetDateTime startAt, OffsetDateTime endAt, Pageable pageable);

        Page<HookSession> findByUserIdAndLastEventAtGreaterThanEqualOrderByLastEventAtDesc(Long userId, OffsetDateTime startAt, Pageable pageable);
        Page<HookSession> findByUserIdAndLastEventAtLessThanOrderByLastEventAtDesc(Long userId, OffsetDateTime endAt, Pageable pageable);
        Page<HookSession> findByUserIdAndLastEventAtGreaterThanEqualAndLastEventAtLessThanOrderByLastEventAtDesc(Long userId, OffsetDateTime startAt, OffsetDateTime endAt, Pageable pageable);
}
