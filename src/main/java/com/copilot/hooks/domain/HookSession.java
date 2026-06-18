package com.copilot.hooks.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(name = "hook_sessions",
        uniqueConstraints = @UniqueConstraint(name = "uk_hook_sessions_user_session", columnNames = {"user_id", "session_id"}))
@Getter
@Setter
public class HookSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Column(columnDefinition = "text")
    private String cwd;

    @Column(length = 32)
    private String source;

    @Column(name = "initial_prompt", columnDefinition = "text")
    private String initialPrompt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "end_reason", length = 32)
    private String endReason;

    @Column(name = "last_event_at")
    private OffsetDateTime lastEventAt;

    @Column(name = "event_count", nullable = false)
    private int eventCount;

    @Column(name = "tool_count", nullable = false)
    private int toolCount;

    @Column(name = "error_count", nullable = false)
    private int errorCount;

    @Column(name = "prompt_count", nullable = false)
    private int promptCount;

    @Column(length = 120)
    private String model;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(name = "cached_tokens", nullable = false)
    private long cachedTokens;

    @Column(name = "copilot_usage_nano_aiu", nullable = false)
    private long copilotUsageNanoAiu;

    @Column(name = "duration_ms")
    private Long durationMs;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
