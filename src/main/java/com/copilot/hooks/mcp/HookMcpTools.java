package com.copilot.hooks.mcp;

import com.copilot.hooks.domain.HookEvent;
import com.copilot.hooks.domain.HookSession;
import com.copilot.hooks.domain.SessionSummary;
import com.copilot.hooks.repository.HookEventRepository;
import com.copilot.hooks.repository.HookSessionRepository;
import com.copilot.hooks.repository.SessionSummaryRepository;
import com.copilot.hooks.security.AppPrincipal;
import com.copilot.hooks.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class HookMcpTools {

    private final HookSessionRepository sessionRepo;
    private final HookEventRepository eventRepo;
    private final SessionSummaryRepository summaryRepo;
    private final VectorStore vectorStore;
    private final CurrentUser currentUser;

    @Tool(name = "search_sessions",
          description = "Semantic search across the caller's Copilot session summaries. Returns top matches with titles, tags, and similarity scores.")
    public List<Map<String, Object>> searchSessions(
            @ToolParam(description = "Free-text query, e.g. 'fixed the OAuth callback bug'") String query,
            @ToolParam(description = "Number of results to return (1-20).") Integer topK) {
        AppPrincipal me = currentUser.require();
        int k = topK == null ? 8 : Math.min(Math.max(topK, 1), 20);
        var filter = new FilterExpressionBuilder().eq("userId", me.userId()).build();
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(k).filterExpression(filter).build());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Document d : docs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionDbId", d.getMetadata().get("sessionDbId"));
            m.put("sessionId", d.getMetadata().get("sessionId"));
            m.put("title", d.getMetadata().get("title"));
            m.put("tags", d.getMetadata().get("tags"));
            m.put("score", d.getScore());
            m.put("snippet", d.getText());
            out.add(m);
        }
        return out;
    }

    @Tool(name = "list_recent_sessions",
          description = "List the caller's most recent Copilot sessions with basic stats.")
    public List<Map<String, Object>> listRecent(
            @ToolParam(description = "Max sessions to return (1-50). Default 20.") Integer limit) {
        AppPrincipal me = currentUser.require();
        int n = limit == null ? 20 : Math.min(Math.max(limit, 1), 50);
        return sessionRepo.findByUserIdOrderByLastEventAtDesc(me.userId(), PageRequest.of(0, n))
                .getContent().stream().map(this::sessionMap).toList();
    }

    @Tool(name = "get_session",
          description = "Fetch a single Copilot session with summary, tags, and the full ordered event/tool-call trace.")
    public Map<String, Object> getSession(
            @ToolParam(description = "Internal numeric sessionDbId (preferred).") Long sessionDbId,
            @ToolParam(description = "Original Copilot sessionId UUID. Used if sessionDbId is null.") String sessionId) {
        AppPrincipal me = currentUser.require();
        Optional<HookSession> s = sessionDbId != null
                ? sessionRepo.findByIdAndUserId(sessionDbId, me.userId())
                : sessionRepo.findByUserIdAndSessionId(me.userId(), sessionId);
        if (s.isEmpty()) return Map.of("error", "not found");
        HookSession session = s.get();
        List<HookEvent> events = eventRepo.findBySessionDbIdOrderByEventTimeAscIdAsc(session.getId());
        SessionSummary summary = summaryRepo.findBySessionDbId(session.getId()).orElse(null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session", sessionMap(session));
        if (summary != null) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("title", summary.getTitle());
            sm.put("summary", summary.getSummary());
            sm.put("highlights", summary.getHighlights());
            sm.put("tags", summary.getTags());
            out.put("summary", sm);
        }
        List<Map<String, Object>> ev = new ArrayList<>();
        for (HookEvent e : events) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", e.getEventType());
            m.put("time", e.getEventTime());
            m.put("tool", e.getToolName());
            m.put("prompt", trim(e.getPrompt(), 1200));
            m.put("toolArgs", e.getToolArgs());
            m.put("toolResult", e.getToolResult());
            m.put("error", trim(e.getErrorMessage(), 400));
            m.put("durationMs", e.getDurationMs());
            m.put("tokens", Map.of(
                    "input", e.getInputTokens(),
                    "output", e.getOutputTokens(),
                    "total", e.getTotalTokens()));
            m.put("model", e.getModel());
            ev.add(m);
        }
        out.put("events", ev);
        return out;
    }

    private Map<String, Object> sessionMap(HookSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionDbId", s.getId());
        m.put("sessionId", s.getSessionId());
        m.put("cwd", s.getCwd());
        m.put("model", s.getModel());
        m.put("startedAt", s.getStartedAt());
        m.put("endedAt", s.getEndedAt());
        m.put("durationMs", s.getDurationMs());
        m.put("eventCount", s.getEventCount());
        m.put("toolCount", s.getToolCount());
        m.put("promptCount", s.getPromptCount());
        m.put("errorCount", s.getErrorCount());
        m.put("totalTokens", s.getTotalTokens());
        return m;
    }

    private static String trim(String s, int n) {
        return s == null ? null : (s.length() <= n ? s : s.substring(0, n) + "…");
    }
}
