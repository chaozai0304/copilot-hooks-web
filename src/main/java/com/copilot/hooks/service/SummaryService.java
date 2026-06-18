package com.copilot.hooks.service;

import com.copilot.hooks.domain.HookEvent;
import com.copilot.hooks.domain.HookSession;
import com.copilot.hooks.domain.ModelConfig;
import com.copilot.hooks.domain.SessionSummary;
import com.copilot.hooks.repository.HookEventRepository;
import com.copilot.hooks.repository.HookSessionRepository;
import com.copilot.hooks.repository.ModelConfigRepository;
import com.copilot.hooks.repository.SessionSummaryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryService {

    private final HookSessionRepository sessionRepo;
    private final HookEventRepository eventRepo;
    private final SessionSummaryRepository summaryRepo;
    private final ModelConfigRepository modelConfigRepo;
    private final ChatClient.Builder chatClientBuilder;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper;

    @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}")
    private String chatModel;

    @Value("${spring.ai.vectorstore.pgvector.schema-name:public}")
    private String vectorSchemaName;

    @Value("${spring.ai.vectorstore.pgvector.table-name:summary_embeddings}")
    private String vectorTableName;

    @Value("${app.summary.auto-on-session-end:true}")
    private boolean autoOnEnd;

    private static final String SYSTEM_PROMPT = """
                        你是一个面向工程师的 GitHub Copilot Agent 会话分析助手。
                        你会收到一段结构化 Trace，其中包含用户问题、工具调用、工具结果、错误、模型和 Token 信息。
                        请整理出便于后续检索、复盘和向量召回的高信号摘要。

                        只允许返回严格 JSON，不要返回 Markdown 代码块，不要输出 JSON 之外的任何解释文字。
                        JSON 结构必须如下：
            {
                            "title": "不超过 80 个中文字符的短标题",
                            "summary": "2-6 句话概括用户意图、关键调用链、处理结果和当前状态",
                            "highlights": "使用中文项目符号整理关键决策、涉及文件、错误、后续动作",
                            "tags": ["3-8 个中文或 kebab-case 标签"]
            }

                        要求：
                        - 优先总结用户真正要解决的问题和最终产出，不要逐条复述无价值日志。
                        - 如果 Trace 中包含工具、模型、Token 或错误信息，请提炼关键事实。
                        - 标签应短小稳定，便于搜索和向量检索。
            """;

    @Async
    @EventListener
    public void onSessionEnded(HookIngestService.SessionEndedEvent ev) {
        if (!autoOnEnd) return;
        try {
            summariseSession(ev.userId(), ev.sessionDbId());
        } catch (Exception e) {
            log.warn("Auto summary failed for session {}: {}", ev.sessionId(), e.toString());
        }
    }

    @Transactional
    public SessionSummary summariseSession(Long userId, Long sessionDbId) {
        HookSession session = sessionRepo.findById(sessionDbId)
                .filter(s -> s.getUserId().equals(userId))
                .orElseThrow(() -> new IllegalArgumentException("session not found"));
        List<HookEvent> events = eventRepo.findBySessionDbIdOrderByEventTimeAscIdAsc(sessionDbId);
        String trace = buildTrace(session, events);
        ModelConfig modelConfig = defaultModelConfigOrNull();

        String raw;
        try {
            raw = chatClient(modelConfig)
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(trace)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("Chat call failed", e);
            throw new RuntimeException("AI summary call failed: " + e.getMessage(), e);
        }

        Map<String, Object> parsed = parseJson(raw);
        String title = String.valueOf(parsed.getOrDefault("title", "Session " + session.getSessionId()));
        String summaryText = String.valueOf(parsed.getOrDefault("summary", ""));
        String highlights = String.valueOf(parsed.getOrDefault("highlights", ""));
        Object tagsRaw = parsed.get("tags");
        String[] tags;
        if (tagsRaw instanceof List<?> list) {
            tags = list.stream().map(String::valueOf).toArray(String[]::new);
        } else {
            tags = new String[0];
        }

        SessionSummary s = summaryRepo.findBySessionDbId(sessionDbId).orElseGet(SessionSummary::new);
        s.setUserId(userId);
        s.setSessionDbId(sessionDbId);
        s.setSessionId(session.getSessionId());
        s.setTitle(truncate(title, 240));
        s.setSummary(summaryText);
        s.setHighlights(highlights);
        s.setTags(tags);
        s.setModel(modelConfig == null ? chatModel : modelConfig.getChatModel());
        SessionSummary saved = summaryRepo.save(s);

        upsertVector(saved, session, modelConfig);
        return saved;
    }

    private void upsertVector(SessionSummary s, HookSession session, ModelConfig modelConfig) {
        try {
            Map<String, Object> meta = new HashMap<>();
            meta.put("userId", s.getUserId());
            meta.put("sessionDbId", s.getSessionDbId());
            meta.put("sessionId", s.getSessionId());
            meta.put("title", s.getTitle() == null ? "" : s.getTitle());
            meta.put("tags", s.getTags() == null ? List.of() : List.of(s.getTags()));
            meta.put("model", session.getModel() == null ? "" : session.getModel());
            meta.put("startedAt", session.getStartedAt() == null ? "" : session.getStartedAt().toString());

            String content = "Title: " + safe(s.getTitle()) + "\n"
                    + "Tags: " + (s.getTags() == null ? "" : String.join(", ", s.getTags())) + "\n\n"
                    + "Summary:\n" + safe(s.getSummary()) + "\n\n"
                    + "Highlights:\n" + safe(s.getHighlights());

            // PgVectorStore uses a UUID primary key. Derive a stable UUID from the session db id
            // so re-summarising replaces the vector instead of appending duplicates.
            String documentId = vectorDocumentId(s.getSessionDbId());
            Document doc = new Document(documentId, content, meta);
            VectorStore store = vectorStore(modelConfig);
            store.delete(List.of(documentId));
            store.add(List.of(doc));
        } catch (Exception e) {
            log.warn("Vector upsert failed for session {}: {}", s.getSessionId(), e.toString());
        }
    }

    private ModelConfig defaultModelConfigOrNull() {
        ModelConfig config = modelConfigRepo.findFirstByEnabledTrueAndDefaultConfigTrueOrderByUpdatedAtDesc().orElse(null);
        if (config == null) return null;
        if (notBlank(config.getBaseUrl()) && notBlank(config.getApiKeyCipher())
                && notBlank(config.getChatModel()) && notBlank(config.getEmbeddingModel())) {
            return config;
        }
        log.warn("Default model config '{}' is incomplete; falling back to application.yml AI configuration", config.getName());
        return null;
    }

    private ChatClient chatClient(ModelConfig config) {
        if (config == null) return chatClientBuilder.build();
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKeyCipher())
                .build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(config.getChatModel())
                        .temperature(0.2)
                        .build())
                .build();
        log.info("Using default model config '{}' for summary chat model={}", config.getName(), config.getChatModel());
        return ChatClient.create(model);
    }

    private VectorStore vectorStore(ModelConfig config) {
        if (config == null) return vectorStore;
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKeyCipher())
                .build();
        OpenAiEmbeddingModel embeddingModel = new OpenAiEmbeddingModel(
                api,
                MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model(config.getEmbeddingModel())
                        .dimensions(config.getEmbeddingDimensions())
                        .build());
        log.info("Using default model config '{}' for summary embedding model={} dimensions={}",
                config.getName(), config.getEmbeddingModel(), config.getEmbeddingDimensions());
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .schemaName(vectorSchemaName)
                .vectorTableName(vectorTableName)
                .dimensions(config.getEmbeddingDimensions())
                .initializeSchema(false)
                .build();
    }

    private String buildTrace(HookSession session, List<HookEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("Session ").append(session.getSessionId()).append('\n');
        sb.append("Cwd: ").append(safe(session.getCwd())).append('\n');
        sb.append("Model: ").append(safe(session.getModel())).append('\n');
        sb.append("Started: ").append(session.getStartedAt())
                .append("  Ended: ").append(session.getEndedAt()).append('\n');
        sb.append("Events: ").append(session.getEventCount())
                .append("  Tools: ").append(session.getToolCount())
                .append("  Prompts: ").append(session.getPromptCount())
                .append("  Errors: ").append(session.getErrorCount()).append('\n');
        if (session.getInitialPrompt() != null) {
            sb.append("Initial prompt: ").append(trim(session.getInitialPrompt(), 800)).append('\n');
        }
        sb.append("\n--- TRACE ---\n");
        int budget = 60_000; // cap input ~60k chars to fit small context windows
        for (HookEvent e : events) {
            if (sb.length() > budget) {
                sb.append("...(trace truncated)...\n");
                break;
            }
            sb.append('[').append(e.getEventTime()).append("] ").append(e.getEventType());
            if (e.getToolName() != null) sb.append("  tool=").append(e.getToolName());
            if (e.getDurationMs() != null) sb.append("  dur=").append(e.getDurationMs()).append("ms");
            if (e.getInputTokens() != null || e.getOutputTokens() != null) {
                sb.append("  tokens=").append(nz(e.getInputTokens())).append('/').append(nz(e.getOutputTokens()));
            }
            sb.append('\n');
            if (e.getPrompt() != null) sb.append("  prompt: ").append(trim(e.getPrompt(), 600)).append('\n');
            if (e.getToolArgs() != null) sb.append("  args: ").append(trim(e.getToolArgs().toString(), 600)).append('\n');
            if (e.getToolResult() != null) sb.append("  result: ").append(trim(e.getToolResult().toString(), 600)).append('\n');
            if (e.getErrorMessage() != null) sb.append("  error: ").append(trim(e.getErrorMessage(), 400)).append('\n');
        }
        return sb.toString();
    }

    private Map<String, Object> parseJson(String raw) {
        if (raw == null) return Map.of();
        String s = raw.trim();
        // Strip fenced code if model returned ```json blocks.
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl >= 0) s = s.substring(firstNl + 1);
            int fence = s.lastIndexOf("```");
            if (fence >= 0) s = s.substring(0, fence);
        }
        s = s.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) s = s.substring(start, end + 1);
        try {
            return mapper.readValue(s, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse summary JSON, returning raw text as summary: {}", e.toString());
            return Map.of("title", "Session summary", "summary", raw, "highlights", "", "tags", List.of());
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static long nz(Long v) { return v == null ? 0L : v; }
    private static String vectorDocumentId(Long sessionDbId) {
        return UUID.nameUUIDFromBytes(("session-summary:" + sessionDbId).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }
    private static String truncate(String s, int n) { return s == null ? null : (s.length() <= n ? s : s.substring(0, n)); }
}
