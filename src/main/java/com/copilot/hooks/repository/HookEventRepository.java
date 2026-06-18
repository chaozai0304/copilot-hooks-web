package com.copilot.hooks.repository;

import com.copilot.hooks.domain.HookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface HookEventRepository extends JpaRepository<HookEvent, Long> {
    List<HookEvent> findBySessionDbIdOrderByEventTimeAscIdAsc(Long sessionDbId);
    List<HookEvent> findBySessionDbIdAndEventTimeGreaterThanEqualOrderByEventTimeAscIdAsc(Long sessionDbId, OffsetDateTime eventTime);
}
