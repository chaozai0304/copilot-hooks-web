package com.copilot.hooks.service;

import com.copilot.hooks.domain.HookEvent;
import com.copilot.hooks.domain.HookSession;
import com.copilot.hooks.repository.HookEventRepository;
import com.copilot.hooks.repository.HookSessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class HookIngestService {

    private final HookSessionRepository sessionRepo;
    private final HookEventRepository eventRepo;
    private final ApplicationEventPublisher publisher;

    public record IngestResult(Long sessionDbId, Long eventId, String eventType, boolean sessionEnded) {}

    @Transactional
    public IngestResult ingest(Long userId, JsonNode payload) {
        String eventType = normaliseEventType(payload);
        String sessionId = textOr(payload, "sessionId", "session_id");
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Missing sessionId/session_id in hook payload");
        }

        OffsetDateTime eventTime = parseTimestamp(payload);
        String cwd = text(payload, "cwd");

        HookSession session = sessionRepo.findByUserIdAndSessionId(userId, sessionId)
                .orElseGet(() -> createSession(userId, sessionId, cwd, eventTime));

        HookEvent event = new HookEvent();
        event.setUserId(userId);
        event.setSessionDbId(session.getId());
        event.setSessionId(sessionId);
        event.setEventType(eventType);
        event.setEventTime(eventTime);
        event.setCwd(cwd);
        event.setRawPayload(payload);

        applyEventDetails(event, session, payload, eventType);

        eventRepo.save(event);

        session.setLastEventAt(eventTime);
        session.setEventCount(session.getEventCount() + 1);
        if (session.getCwd() == null && cwd != null) session.setCwd(cwd);

        boolean sessionEnded = false;
        switch (eventType) {
            case "sessionStart" -> {
                if (session.getStartedAt() == null) session.setStartedAt(eventTime);
                String src = text(payload, "source");
                if (src != null) session.setSource(src);
                String init = textOr(payload, "initialPrompt", "initial_prompt");
                if (init != null) session.setInitialPrompt(init);
            }
            case "sessionEnd" -> {
                session.setEndedAt(eventTime);
                session.setEndReason(text(payload, "reason"));
                if (session.getStartedAt() != null) {
                    session.setDurationMs(session.getEndedAt().toInstant().toEpochMilli()
                            - session.getStartedAt().toInstant().toEpochMilli());
                }
                sessionEnded = true;
            }
            case "userPromptSubmitted" -> {
                session.setPromptCount(session.getPromptCount() + 1);
                if ((session.getInitialPrompt() == null || session.getInitialPrompt().isBlank())
                        && event.getPrompt() != null && !event.getPrompt().isBlank()) {
                    session.setInitialPrompt(event.getPrompt());
                }
            }
            case "preToolUse" -> session.setToolCount(session.getToolCount() + 1);
            case "errorOccurred", "postToolUseFailure" -> session.setErrorCount(session.getErrorCount() + 1);
            default -> { /* nothing extra */ }
        }
        if (event.getInputTokens() != null) session.setInputTokens(session.getInputTokens() + event.getInputTokens());
        if (event.getOutputTokens() != null) session.setOutputTokens(session.getOutputTokens() + event.getOutputTokens());
        if (event.getCachedTokens() != null) session.setCachedTokens(session.getCachedTokens() + event.getCachedTokens());
        if (event.getTotalTokens() != null) session.setTotalTokens(session.getTotalTokens() + event.getTotalTokens());
        if (event.getCopilotUsageNanoAiu() != null) session.setCopilotUsageNanoAiu(session.getCopilotUsageNanoAiu() + event.getCopilotUsageNanoAiu());
        if (event.getModel() != null) session.setModel(event.getModel());

        sessionRepo.save(session);

        IngestResult result = new IngestResult(session.getId(), event.getId(), eventType, sessionEnded);
        if (sessionEnded) {
            publisher.publishEvent(new SessionEndedEvent(userId, session.getId(), sessionId));
        }
        return result;
    }

    private HookSession createSession(Long userId, String sessionId, String cwd, OffsetDateTime eventTime) {
        HookSession s = new HookSession();
        s.setUserId(userId);
        s.setSessionId(sessionId);
        s.setCwd(cwd);
        s.setLastEventAt(eventTime);
        s.setStartedAt(eventTime);
        return sessionRepo.save(s);
    }

    private void applyEventDetails(HookEvent event, HookSession session, JsonNode p, String type) {
        String tool = textOr(p, "toolName", "tool_name");
        if (tool != null) event.setToolName(tool);

        JsonNode args = firstNode(p, "toolArgs", "tool_args", "tool_input");
        if (args != null && !args.isNull()) event.setToolArgs(args);

        JsonNode result = firstNode(p, "toolResult", "tool_result", "tool_response", "tool_calls");
        if (result != null && !result.isNull()) event.setToolResult(result);

        String prompt = textOr(p, "prompt", "userPrompt", "user_prompt", "last_assistant_message", "transcript_last_user_message", "delta", "message", "input", "text", "content");
        if (prompt == null) prompt = nestedText(p.get("transcript_enrichment"), "last_assistant_message", "last_user_message", "content");
        if (prompt != null) event.setPrompt(prompt);

        JsonNode err = p.get("error");
        if (err != null) {
            if (err.isTextual()) event.setErrorMessage(err.asText());
            else if (err.isObject()) event.setErrorMessage(err.path("message").asText(null));
        }

        // Token / model accounting: not standard in the payload but support common fields.
        Long inputT = longOr(p, "inputTokens", "input_tokens", "promptTokens", "prompt_tokens");
        Long outputT = longOr(p, "outputTokens", "output_tokens", "completionTokens", "completion_tokens");
        Long cachedT = longOr(p, "cachedTokens", "cached_tokens", "cachedInputTokens", "cacheInputTokens");
        Long totalT = longOr(p, "totalTokens", "total_tokens", "total");
        String model = textOr(p, "model", "modelName", "model_name", "resolvedModel", "resolved_model");
        Long dur = longOr(p, "durationMs", "duration_ms");
        Long ttft = longOr(p, "ttftMs", "ttft_ms", "ttft");
        Long usageNanoAiu = longOr(p, "copilotUsageNanoAiu", "copilot_usage_nano_aiu");

        if (inputT == null) inputT = nestedLong(p, "inputTokens", "input_tokens", "promptTokens", "prompt_tokens");
        if (outputT == null) outputT = nestedLong(p, "outputTokens", "output_tokens", "completionTokens", "completion_tokens");
        if (cachedT == null) cachedT = nestedLong(p, "cachedTokens", "cached_tokens", "cachedInputTokens", "cacheInputTokens");
        if (totalT == null) totalT = nestedLong(p, "totalTokens", "total_tokens", "total");
        if (model == null) model = nestedText(p, "model", "modelName", "model_name", "resolvedModel", "resolved_model");
        if (dur == null) dur = nestedLong(p, "durationMs", "duration_ms", "elapsedMs", "elapsed_ms");
        if (ttft == null) ttft = nestedLong(p, "ttftMs", "ttft_ms", "ttft");
        if (usageNanoAiu == null) usageNanoAiu = nestedLong(p, "copilotUsageNanoAiu", "copilot_usage_nano_aiu");

        // Sometimes fields live inside toolResult or postToolUse payload.
        if (result != null) {
            if (inputT == null) inputT = longOr(result, "inputTokens", "input_tokens", "promptTokens", "prompt_tokens");
            if (outputT == null) outputT = longOr(result, "outputTokens", "output_tokens", "completionTokens", "completion_tokens");
            if (cachedT == null) cachedT = longOr(result, "cachedTokens", "cached_tokens", "cachedInputTokens", "cacheInputTokens");
            if (totalT == null) totalT = longOr(result, "totalTokens", "total_tokens");
            if (model == null) model = textOr(result, "model", "modelName", "model_name", "resolvedModel", "resolved_model");
            if (dur == null) dur = longOr(result, "durationMs", "duration_ms");
            if (ttft == null) ttft = longOr(result, "ttftMs", "ttft_ms", "ttft");
            if (usageNanoAiu == null) usageNanoAiu = longOr(result, "copilotUsageNanoAiu", "copilot_usage_nano_aiu");
        }

        event.setInputTokens(inputT);
        event.setOutputTokens(outputT);
        event.setCachedTokens(cachedT);
        event.setTotalTokens(totalT != null ? totalT
                : (inputT != null && outputT != null ? inputT + outputT : null));
        event.setModel(model);
        event.setDurationMs(dur);
        event.setTtftMs(ttft);
        event.setCopilotUsageNanoAiu(usageNanoAiu);
    }

    private String normaliseEventType(JsonNode payload) {
        String t = textOr(payload, "hook_event_name", "hookEventName", "event", "eventType", "event_type");
        if (t == null || t.isBlank()) return "unknown";
        return switch (t) {
            case "SessionStart" -> "sessionStart";
            case "SessionEnd" -> "sessionEnd";
            case "UserPromptSubmit" -> "userPromptSubmitted";
            case "PreToolUse" -> "preToolUse";
            case "PostToolUse" -> "postToolUse";
            case "PostToolBatch" -> "postToolBatch";
            case "PostToolUseFailure" -> "postToolUseFailure";
            case "Stop" -> "agentStop";
            case "StopFailure" -> "stopFailure";
            case "SubagentStart" -> "subagentStart";
            case "SubagentStop" -> "subagentStop";
            case "ErrorOccurred" -> "errorOccurred";
            case "PreCompact" -> "preCompact";
            case "PostCompact" -> "postCompact";
            case "Notification" -> "notification";
            case "MessageDisplay" -> "messageDisplay";
            case "PermissionRequest" -> "permissionRequest";
            case "PermissionDenied" -> "permissionDenied";
            default -> t;
        };
    }

    private OffsetDateTime parseTimestamp(JsonNode p) {
        JsonNode ts = p.get("timestamp");
        if (ts == null || ts.isNull()) return OffsetDateTime.now();
        if (ts.isNumber()) {
            long v = ts.asLong();
            // Heuristic: <= 10 digits is seconds, otherwise milliseconds.
            Instant inst = v < 100000000000L ? Instant.ofEpochSecond(v) : Instant.ofEpochMilli(v);
            return inst.atZone(ZoneId.systemDefault()).toOffsetDateTime();
        }
        if (ts.isTextual()) {
            try {
                return OffsetDateTime.parse(ts.asText(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } catch (Exception ignored) {
                try {
                    return Instant.parse(ts.asText()).atZone(ZoneId.systemDefault()).toOffsetDateTime();
                } catch (Exception ignored2) {
                    return OffsetDateTime.now();
                }
            }
        }
        return OffsetDateTime.now();
    }

    private static String text(JsonNode n, String key) {
        if (n == null) return null;
        JsonNode v = n.get(key);
        return (v == null || v.isNull()) ? null : (v.isTextual() ? v.asText() : v.toString());
    }

    private static String textOr(JsonNode n, String... keys) {
        for (String k : keys) {
            String v = text(n, k);
            if (v != null) return v;
        }
        return null;
    }

    private static JsonNode firstNode(JsonNode n, String... keys) {
        if (n == null) return null;
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && !v.isNull()) return v;
        }
        return null;
    }

    private static Long longOr(JsonNode n, String... keys) {
        if (n == null) return null;
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && v.canConvertToLong()) return v.asLong();
            if (v != null && v.isTextual()) {
                try { return Long.parseLong(v.asText()); } catch (NumberFormatException ignored) { }
            }
        }
        return null;
    }

    private static Long nestedLong(JsonNode n, String... keys) {
        if (n == null) return null;
        Long direct = longOr(n, keys);
        if (direct != null) return direct;
        if (n.isObject() || n.isArray()) {
            for (JsonNode child : n) {
                Long hit = nestedLong(child, keys);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static String nestedText(JsonNode n, String... keys) {
        if (n == null) return null;
        String direct = textOr(n, keys);
        if (direct != null && !direct.isBlank()) return direct;
        if (n.isObject() || n.isArray()) {
            for (JsonNode child : n) {
                String hit = nestedText(child, keys);
                if (hit != null && !hit.isBlank()) return hit;
            }
        }
        return null;
    }

    public record SessionEndedEvent(Long userId, Long sessionDbId, String sessionId) {}
}
