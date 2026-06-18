package com.copilot.hooks.controller;

import com.copilot.hooks.domain.HookEvent;
import com.copilot.hooks.domain.HookSession;
import com.copilot.hooks.domain.SessionSummary;
import com.copilot.hooks.repository.HookEventRepository;
import com.copilot.hooks.repository.HookSessionRepository;
import com.copilot.hooks.repository.SessionSummaryRepository;
import com.copilot.hooks.security.AppPrincipal;
import com.copilot.hooks.security.CurrentUser;
import com.copilot.hooks.service.SummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final HookSessionRepository sessionRepo;
    private final HookEventRepository eventRepo;
    private final SessionSummaryRepository summaryRepo;
    private final SummaryService summaryService;
    private final CurrentUser currentUser;

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(required = false) String month,
                                    @RequestParam(required = false) String day,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(required = false) Long userId) {
        AppPrincipal me = currentUser.require();
        boolean admin = "ADMIN".equalsIgnoreCase(me.role());
        PageRequest pageable = PageRequest.of(page, Math.min(size, 100));

        TimeRange range = resolveRange(month, day, from, to);
        Long filterUserId = admin ? userId : me.userId();
        Page<HookSession> p = listSessions(filterUserId, range, pageable);

        Map<Long, SessionSummary> summaries = new HashMap<>();
        if (admin && filterUserId == null) {
            p.getContent().forEach(s -> summaryRepo.findBySessionDbId(s.getId()).ifPresent(sum -> summaries.put(s.getId(), sum)));
        } else {
            Long summaryUserId = filterUserId == null ? me.userId() : filterUserId;
            summaryRepo.findByUserIdOrderByUpdatedAtDesc(summaryUserId)
                .forEach(s -> summaries.put(s.getSessionDbId(), s));
        }
        return Map.of(
                "total", p.getTotalElements(),
                "page", p.getNumber(),
                "size", p.getSize(),
                "items", p.getContent().stream().map(s -> sessionView(s, summaries.get(s.getId()))).toList()
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id,
                                 @RequestParam(name = "all", defaultValue = "false") boolean allEvents) {
        AppPrincipal me = currentUser.require();
        return findVisibleSession(id, me)
                .map(s -> {
                    OffsetDateTime since = OffsetDateTime.now().minusDays(7);
                    List<HookEvent> events = allEvents
                            ? eventRepo.findBySessionDbIdOrderByEventTimeAscIdAsc(s.getId())
                            : eventRepo.findBySessionDbIdAndEventTimeGreaterThanEqualOrderByEventTimeAscIdAsc(s.getId(), since);
                    SessionSummary summary = summaryRepo.findBySessionDbId(s.getId()).orElse(null);
                    Map<String, Object> body = new HashMap<>();
                    body.put("session", sessionView(s, summary));
                    body.put("summary", summary == null ? null : summaryView(summary));
                    body.put("events", events.stream().map(this::eventView).toList());
                    body.put("eventRange", allEvents ? "all" : "recent_week");
                    return ResponseEntity.ok(body);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private record TimeRange(OffsetDateTime startAt, OffsetDateTime endAt) {}

    private Page<HookSession> listSessions(Long filterUserId, TimeRange range, PageRequest pageable) {
        OffsetDateTime startAt = range.startAt();
        OffsetDateTime endAt = range.endAt();
        if (filterUserId == null) {
            if (startAt != null && endAt != null) {
                return sessionRepo.findAllByLastEventAtGreaterThanEqualAndLastEventAtLessThanOrderByLastEventAtDesc(startAt, endAt, pageable);
            }
            if (startAt != null) {
                return sessionRepo.findAllByLastEventAtGreaterThanEqualOrderByLastEventAtDesc(startAt, pageable);
            }
            if (endAt != null) {
                return sessionRepo.findAllByLastEventAtLessThanOrderByLastEventAtDesc(endAt, pageable);
            }
            return sessionRepo.findAllByOrderByLastEventAtDesc(pageable);
        }

        if (startAt != null && endAt != null) {
            return sessionRepo.findByUserIdAndLastEventAtGreaterThanEqualAndLastEventAtLessThanOrderByLastEventAtDesc(filterUserId, startAt, endAt, pageable);
        }
        if (startAt != null) {
            return sessionRepo.findByUserIdAndLastEventAtGreaterThanEqualOrderByLastEventAtDesc(filterUserId, startAt, pageable);
        }
        if (endAt != null) {
            return sessionRepo.findByUserIdAndLastEventAtLessThanOrderByLastEventAtDesc(filterUserId, endAt, pageable);
        }
        return sessionRepo.findByUserIdOrderByLastEventAtDesc(filterUserId, pageable);
    }

    private TimeRange resolveRange(String month, String day, String from, String to) {
        ZoneId zone = ZoneId.systemDefault();
        if (day != null && !day.isBlank()) {
            LocalDate d = LocalDate.parse(day);
            return new TimeRange(d.atStartOfDay(zone).toOffsetDateTime(), d.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
        }
        if (month != null && !month.isBlank()) {
            YearMonth ym = YearMonth.parse(month);
            LocalDate start = ym.atDay(1);
            return new TimeRange(start.atStartOfDay(zone).toOffsetDateTime(), start.plusMonths(1).atStartOfDay(zone).toOffsetDateTime());
        }
        OffsetDateTime startAt = null;
        OffsetDateTime endAt = null;
        if (from != null && !from.isBlank()) {
            startAt = LocalDate.parse(from).atStartOfDay(zone).toOffsetDateTime();
        }
        if (to != null && !to.isBlank()) {
            endAt = LocalDate.parse(to).plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        }
        return new TimeRange(startAt, endAt);
    }

    @PostMapping("/{id}/summary")
    public ResponseEntity<?> regenerate(@PathVariable Long id) {
        AppPrincipal me = currentUser.require();
        return findVisibleSession(id, me)
                .map(s -> {
                    SessionSummary summary = summaryService.summariseSession(s.getUserId(), s.getId());
                    return ResponseEntity.ok(summaryView(summary));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private java.util.Optional<HookSession> findVisibleSession(Long id, AppPrincipal me) {
        if ("ADMIN".equalsIgnoreCase(me.role())) {
            return sessionRepo.findById(id);
        }
        return sessionRepo.findByIdAndUserId(id, me.userId());
    }

    private Map<String, Object> sessionView(HookSession s, SessionSummary summary) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", s.getId());
        m.put("userId", s.getUserId());
        m.put("sessionId", s.getSessionId());
        m.put("cwd", s.getCwd());
        m.put("source", s.getSource());
        m.put("initialPrompt", cleanPrompt(firstPromptForSession(s)));
        m.put("startedAt", s.getStartedAt());
        m.put("endedAt", s.getEndedAt());
        m.put("endReason", s.getEndReason());
        m.put("lastEventAt", s.getLastEventAt());
        m.put("eventCount", s.getEventCount());
        m.put("toolCount", s.getToolCount());
        m.put("errorCount", s.getErrorCount());
        m.put("promptCount", s.getPromptCount());
        m.put("model", s.getModel());
        m.put("totalTokens", s.getTotalTokens());
        m.put("inputTokens", s.getInputTokens());
        m.put("outputTokens", s.getOutputTokens());
        m.put("cachedTokens", s.getCachedTokens());
        m.put("copilotUsageAiu", s.getCopilotUsageNanoAiu() / 1_000_000_000d);
        m.put("durationMs", s.getDurationMs());
        if (summary != null) {
            m.put("title", summary.getTitle());
            m.put("tags", summary.getTags());
        }
        return m;
    }

    private Map<String, Object> eventView(HookEvent e) {
        Map<String, Object> m = new HashMap<>();
        Long inputTokens = firstLong(e.getInputTokens(), nestedLong(e.getRawPayload(), "inputTokens", "input_tokens", "promptTokens", "prompt_tokens"));
        Long outputTokens = firstLong(e.getOutputTokens(), nestedLong(e.getRawPayload(), "outputTokens", "output_tokens", "completionTokens", "completion_tokens"));
        Long cachedTokens = firstLong(e.getCachedTokens(), nestedLong(e.getRawPayload(), "cachedTokens", "cached_tokens", "cachedInputTokens", "cacheInputTokens"));
        Long totalTokens = firstLong(e.getTotalTokens(), nestedLong(e.getRawPayload(), "totalTokens", "total_tokens", "total"));
        if (totalTokens == null && inputTokens != null && outputTokens != null) totalTokens = inputTokens + outputTokens;
        String model = firstText(e.getModel(), nestedText(e.getRawPayload(), "model", "modelName", "model_name"));
        Long ttftMs = firstLong(e.getTtftMs(), nestedLong(e.getRawPayload(), "ttftMs", "ttft_ms", "ttft"));
        Long usageNanoAiu = firstLong(e.getCopilotUsageNanoAiu(), nestedLong(e.getRawPayload(), "copilotUsageNanoAiu", "copilot_usage_nano_aiu"));
        Double cost = nestedDouble(e.getRawPayload(), "cost", "costUsd", "cost_usd", "credits", "credit", "points");
        Double copilotUsageAiu = usageNanoAiu == null
            ? nestedDouble(e.getRawPayload(), "copilotUsageAiu", "copilot_usage_aiu")
            : Double.valueOf(usageNanoAiu / 1_000_000_000d);
        if (cost == null) cost = copilotUsageAiu;
        m.put("id", e.getId());
        m.put("seq", e.getSeq());
        m.put("sessionId", e.getSessionId());
        m.put("type", e.getEventType());
        m.put("time", e.getEventTime());
        m.put("receivedAt", e.getCreatedAt());
        m.put("createdAt", e.getCreatedAt());
        m.put("rawTimestamp", text(e.getRawPayload(), "timestamp"));
        m.put("transcriptPath", text(e.getRawPayload(), "transcript_path"));
        m.put("cwd", e.getCwd());
        m.put("tool", e.getToolName());
        m.put("toolArgs", e.getToolArgs());
        m.put("toolResult", e.getToolResult());
        m.put("prompt", cleanPrompt(e.getPrompt()));
        m.put("error", e.getErrorMessage());
        m.put("durationMs", e.getDurationMs());
        m.put("inputTokens", inputTokens);
        m.put("outputTokens", outputTokens);
        m.put("cachedTokens", cachedTokens);
        m.put("totalTokens", totalTokens);
        m.put("ttftMs", ttftMs);
        m.put("copilotUsageAiu", copilotUsageAiu);
        m.put("modelUsageEvents", e.getRawPayload() == null ? null : e.getRawPayload().get("model_usage_events"));
        m.put("model", model);
        m.put("cost", cost);
        m.put("raw", e.getRawPayload());
        return m;
    }

    private Long firstLong(Long primary, Long fallback) {
        return primary != null ? primary : fallback;
    }

    private String firstText(String primary, String fallback) {
        return primary != null && !primary.isBlank() ? primary : fallback;
    }

    private Long nestedLong(com.fasterxml.jackson.databind.JsonNode node, String... keys) {
        Double v = nestedNumber(node, keys, 0);
        return v == null ? null : v.longValue();
    }

    private Double nestedDouble(com.fasterxml.jackson.databind.JsonNode node, String... keys) {
        return nestedNumber(node, keys, 0);
    }

    private Double nestedNumber(com.fasterxml.jackson.databind.JsonNode node, String[] keys, int depth) {
        if (node == null || node.isNull() || depth > 6) return null;
        if (node.isObject()) {
            for (String key : keys) {
                com.fasterxml.jackson.databind.JsonNode v = node.get(key);
                if (v != null && v.isNumber()) return v.asDouble();
                if (v != null && v.isTextual()) {
                    try { return Double.parseDouble(v.asText()); } catch (NumberFormatException ignored) { }
                }
            }
            for (com.fasterxml.jackson.databind.JsonNode child : node) {
                Double hit = nestedNumber(child, keys, depth + 1);
                if (hit != null) return hit;
            }
        } else if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode child : node) {
                Double hit = nestedNumber(child, keys, depth + 1);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private String nestedText(com.fasterxml.jackson.databind.JsonNode node, String... keys) {
        return nestedText(node, keys, 0);
    }

    private String text(com.fasterxml.jackson.databind.JsonNode node, String key) {
        if (node == null || node.isNull()) return null;
        com.fasterxml.jackson.databind.JsonNode v = node.get(key);
        return v == null || v.isNull() ? null : (v.isTextual() ? v.asText() : v.toString());
    }

    private String nestedText(com.fasterxml.jackson.databind.JsonNode node, String[] keys, int depth) {
        if (node == null || node.isNull() || depth > 6) return null;
        if (node.isObject()) {
            for (String key : keys) {
                com.fasterxml.jackson.databind.JsonNode v = node.get(key);
                if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText();
            }
            for (com.fasterxml.jackson.databind.JsonNode child : node) {
                String hit = nestedText(child, keys, depth + 1);
                if (hit != null) return hit;
            }
        } else if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode child : node) {
                String hit = nestedText(child, keys, depth + 1);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private String firstPromptForSession(HookSession s) {
        if (s.getInitialPrompt() != null && !s.getInitialPrompt().isBlank()) {
            return s.getInitialPrompt();
        }
        return eventRepo.findBySessionDbIdOrderByEventTimeAscIdAsc(s.getId()).stream()
                .map(HookEvent::getPrompt)
                .filter(p -> p != null && !p.isBlank())
                .findFirst()
                .orElse(null);
    }

    private String cleanPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) return prompt;
        String cleaned = prompt;
        String[] cutMarkers = {
                "**Summary**", "<analysis>", "</analysis>", "[Chronological Review:",
                "[Intent Mapping:", "[Technical Inventory:", "[Code Archaeology:",
                "[Progress Assessment:", "[Context Validation:", "[Recent Commands Analysis:"
        };
        for (String marker : cutMarkers) {
            int idx = cleaned.indexOf(marker);
            if (idx >= 0) cleaned = cleaned.substring(0, idx);
        }
        cleaned = cleaned.strip();
        return cleaned.isBlank() ? null : cleaned;
    }

    private Map<String, Object> summaryView(SessionSummary s) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", s.getTitle());
        m.put("summary", s.getSummary());
        m.put("highlights", s.getHighlights());
        m.put("tags", s.getTags());
        m.put("model", s.getModel());
        m.put("tokenUsage", s.getTokenUsage());
        m.put("updatedAt", s.getUpdatedAt());
        return m;
    }
}
