package com.copilot.hooks.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "hook_events")
@Getter
@Setter
public class HookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_db_id", nullable = false)
    private Long sessionDbId;

    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Column(name = "event_type", nullable = false, length = 48)
    private String eventType;

    @Column(name = "event_time", nullable = false)
    private OffsetDateTime eventTime;

    @Column(insertable = false, updatable = false)
    private Long seq;

    @Column(columnDefinition = "text")
    private String cwd;

    @Column(name = "tool_name", length = 120)
    private String toolName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_args", columnDefinition = "jsonb")
    private JsonNode toolArgs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_result", columnDefinition = "jsonb")
    private JsonNode toolResult;

    @Column(columnDefinition = "text")
    private String prompt;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "input_tokens")
    private Long inputTokens;

    @Column(name = "output_tokens")
    private Long outputTokens;

    @Column(name = "cached_tokens")
    private Long cachedTokens;

    @Column(name = "total_tokens")
    private Long totalTokens;

    @Column(name = "ttft_ms")
    private Long ttftMs;

    @Column(name = "copilot_usage_nano_aiu")
    private Long copilotUsageNanoAiu;

    @Column(length = 120)
    private String model;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode rawPayload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
