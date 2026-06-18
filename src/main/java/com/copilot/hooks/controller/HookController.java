package com.copilot.hooks.controller;

import com.copilot.hooks.security.AppPrincipal;
import com.copilot.hooks.security.CurrentUser;
import com.copilot.hooks.service.HookIngestService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/hooks")
@RequiredArgsConstructor
public class HookController {

    private final HookIngestService ingestService;
    private final ObjectMapper mapper;
    private final CurrentUser currentUser;

    /**
     * Hook entrypoint. Accepts a single JSON event (camelCase or PascalCase),
     * or an array of events, or NDJSON (one JSON object per line) when content-type is text/plain.
     */
    @PostMapping(value = "/ingest")
    public ResponseEntity<?> ingest(HttpServletRequest request) throws Exception {
        long startedNs = System.nanoTime();
        AppPrincipal me = currentUser.require();

        String contentType = request.getContentType();
        String source = request.getHeader("X-Source");
        String remoteAddr = request.getRemoteAddr();
        List<HookIngestService.IngestResult> results = new ArrayList<>();

        log.info("Hook ingest request received userId={} username={} source={} remote={} contentType={}",
                me.userId(), me.username(), safe(source), safe(remoteAddr), safe(contentType));

        if (contentType != null && contentType.contains("ndjson")) {
            try (BufferedReader r = request.getReader()) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    JsonNode node = mapper.readTree(line);
                    results.add(ingestOne(me, node));
                }
            }
        } else {
            JsonNode body;
            try (BufferedReader r = request.getReader()) {
                body = mapper.readTree(r);
            }
            if (body == null || body.isMissingNode() || body.isNull()) {
                return ResponseEntity.badRequest().body(Map.of("error", "empty body"));
            }
            if (body.isArray()) {
                for (JsonNode el : body) results.add(ingestOne(me, el));
            } else {
                results.add(ingestOne(me, body));
            }
        }

        long elapsedMs = (System.nanoTime() - startedNs) / 1_000_000;
        log.info("Hook ingest request completed userId={} received={} elapsedMs={} results={}",
                me.userId(), results.size(), elapsedMs, results);

        // Returning {} keeps the agent flow unchanged (no permission override, no extra context).
        return ResponseEntity.ok(Map.of(
                "received", results.size(),
                "results", results
        ));
    }

    @GetMapping(value = "/ping", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> ping() {
        AppPrincipal me = currentUser.require();
        return Map.of("ok", true, "user", me.username());
    }

    private HookIngestService.IngestResult ingestOne(AppPrincipal me, JsonNode node) {
        String eventName = textOr(node, "hook_event_name", "hookEventName", "event", "eventType", "event_type");
        String sessionId = textOr(node, "session_id", "sessionId");
        String timestamp = text(node, "timestamp");
        String transcriptPath = text(node, "transcript_path");
        String toolName = textOr(node, "tool_name", "toolName");
        JsonNode forwarder = node.path("hook_forwarder");
        JsonNode enrichment = node.path("transcript_enrichment");
        boolean transcriptRead = forwarder.path("transcript_read").asBoolean(false);
        boolean usageDebugRead = forwarder.path("usage_debug").path("debug_log_read").asBoolean(false);
        boolean hasLastAssistant = enrichment.hasNonNull("last_assistant_message") || node.hasNonNull("last_assistant_message");
        int recentMessages = enrichment.path("recent_messages").isArray() ? enrichment.path("recent_messages").size() : 0;
        int recentTools = enrichment.path("recent_tool_events").isArray() ? enrichment.path("recent_tool_events").size() : 0;
        log.info("Hook event ingest start userId={} event={} sessionId={} timestamp={} tool={} transcriptPath={} transcriptRead={} usageDebugRead={} hasLastAssistant={} recentMessages={} recentTools={} inputTokens={} outputTokens={} cachedTokens={} totalTokens={} model={}",
            me.userId(), safe(eventName), safe(sessionId), safe(timestamp), safe(toolName), safe(transcriptPath),
            transcriptRead, usageDebugRead, hasLastAssistant, recentMessages, recentTools,
            text(node, "inputTokens"), text(node, "outputTokens"), text(node, "cachedTokens"), text(node, "totalTokens"), safe(text(node, "model")));
        if (log.isDebugEnabled()) {
            log.debug("Hook event raw payload userId={} sessionId={} payload={}", me.userId(), safe(sessionId), node);
        }
        HookIngestService.IngestResult result = ingestService.ingest(me.userId(), node);
        log.info("Hook event ingest done userId={} sessionDbId={} eventId={} eventType={} sessionEnded={}",
                me.userId(), result.sessionDbId(), result.eventId(), result.eventType(), result.sessionEnded());
        return result;
    }

    private static String text(JsonNode n, String key) {
        if (n == null) return null;
        JsonNode v = n.get(key);
        return (v == null || v.isNull()) ? null : (v.isTextual() ? v.asText() : v.toString());
    }

    private static String textOr(JsonNode n, String... keys) {
        for (String k : keys) {
            String v = text(n, k);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "-";
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }
}
