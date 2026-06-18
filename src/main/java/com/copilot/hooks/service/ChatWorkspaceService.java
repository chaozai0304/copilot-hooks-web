package com.copilot.hooks.service;

import com.copilot.hooks.domain.ModelConfig;
import com.copilot.hooks.repository.ModelConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatWorkspaceService {

    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是 copilot-hooks-web 内置的中文工程助手。
            你的目标是帮助用户围绕 Copilot Hook 会话、内容整理、检索和排障完成分析与执行建议。
            当 MCP 工具可用时，请优先在需要事实依据时调用工具，而不是编造结果。
            回答保持简洁、准确、结构化。
            """;

    private final ModelConfigRepository modelConfigRepository;
    private final ChatClient.Builder chatClientBuilder;
    private final ToolCallbackProvider hookMcpToolCallbacks;

    @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}")
    private String fallbackChatModel;

    public Map<String, Object> options() {
        List<Map<String, Object>> models = modelConfigRepository.findByEnabledTrueOrderByUpdatedAtDesc().stream()
                .map(this::toModelOption)
                .toList();
        List<Map<String, Object>> tools = Arrays.stream(hookMcpToolCallbacks.getToolCallbacks())
                .map(this::toToolOption)
                .toList();
        Long defaultModelId = modelConfigRepository.findFirstByEnabledTrueAndDefaultConfigTrueOrderByUpdatedAtDesc()
                .map(ModelConfig::getId)
                .orElse(null);
        return Map.of(
                "defaultModelId", defaultModelId,
                "models", models,
                "mcpTools", tools,
                "fallbackModel", fallbackChatModel,
                "defaultSystemPrompt", DEFAULT_SYSTEM_PROMPT
        );
    }

    public Map<String, Object> chat(ChatRequest request) {
        ModelConfig modelConfig = resolveModelConfig(request.modelConfigId());
        OpenAiChatOptions options = buildOptions(modelConfig, request);
        ChatClient chatClient = chatClient(modelConfig);

        List<Message> messages = new ArrayList<>();
        String systemPrompt = blankToNull(request.systemPrompt());
        if (systemPrompt == null) systemPrompt = DEFAULT_SYSTEM_PROMPT;
        messages.add(new SystemMessage(systemPrompt));
        for (ChatTurn turn : request.messages()) {
            if (turn == null || blankToNull(turn.content()) == null) continue;
            String role = blankToNull(turn.role()) == null ? "user" : turn.role().trim().toLowerCase();
            if ("assistant".equals(role)) {
                messages.add(new AssistantMessage(turn.content()));
            } else if ("system".equals(role)) {
                messages.add(new SystemMessage(turn.content()));
            } else {
                messages.add(new UserMessage(turn.content()));
            }
        }

        var spec = chatClient.prompt().messages(messages).options(options);
        if (Boolean.TRUE.equals(request.useAllMcp())) {
            spec = spec.toolCallbacks(hookMcpToolCallbacks);
        } else if (request.toolNames() != null && !request.toolNames().isEmpty()) {
            spec = spec.toolCallbacks(hookMcpToolCallbacks).toolNames(request.toolNames().toArray(String[]::new));
        }

        ChatResponse response = spec.call().chatResponse();
        AssistantMessage assistant = response.getResult().getOutput();
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", assistant == null ? "" : assistant.getText());
        out.put("model", response.getMetadata() != null && response.getMetadata().getModel() != null
                ? response.getMetadata().getModel()
                : modelConfig == null ? fallbackChatModel : modelConfig.getChatModel());
        out.put("toolCalls", assistant == null ? List.of() : assistant.getToolCalls().stream().map(tc -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", tc.id());
            m.put("type", tc.type());
            m.put("name", tc.name());
            m.put("arguments", tc.arguments());
            return m;
        }).toList());
        Map<String, Object> usageMap = new LinkedHashMap<>();
        if (usage != null) {
            usageMap.put("inputTokens", usage.getPromptTokens());
            usageMap.put("outputTokens", usage.getCompletionTokens());
            usageMap.put("totalTokens", usage.getTotalTokens());
        }
        out.put("usage", usageMap);
        return out;
    }

    private OpenAiChatOptions buildOptions(ModelConfig config, ChatRequest request) {
        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setModel(config == null ? fallbackChatModel : config.getChatModel());
        options.setTemperature(request.temperature() == null ? 0.2d : request.temperature());
        if (request.topP() != null) options.setTopP(request.topP());
        if (request.maxTokens() != null) options.setMaxTokens(request.maxTokens());
        options.setInternalToolExecutionEnabled(true);
        return options;
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
        return ChatClient.create(model);
    }

    private ModelConfig resolveModelConfig(Long modelConfigId) {
        if (modelConfigId != null) {
            ModelConfig config = modelConfigRepository.findById(modelConfigId)
                    .filter(ModelConfig::isEnabled)
                    .orElseThrow(() -> new IllegalArgumentException("指定模型配置不存在或未启用"));
            if (blankToNull(config.getBaseUrl()) == null || blankToNull(config.getApiKeyCipher()) == null || blankToNull(config.getChatModel()) == null) {
                throw new IllegalArgumentException("指定模型配置不完整，缺少 Base URL / API Key / Chat Model");
            }
            return config;
        }
        return modelConfigRepository.findFirstByEnabledTrueAndDefaultConfigTrueOrderByUpdatedAtDesc()
                .filter(c -> blankToNull(c.getBaseUrl()) != null && blankToNull(c.getApiKeyCipher()) != null && blankToNull(c.getChatModel()) != null)
                .orElse(null);
    }

    private Map<String, Object> toModelOption(ModelConfig modelConfig) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", modelConfig.getId());
        out.put("name", modelConfig.getName());
        out.put("provider", modelConfig.getProvider());
        out.put("chatModel", modelConfig.getChatModel());
        out.put("enabled", modelConfig.isEnabled());
        out.put("defaultConfig", modelConfig.isDefaultConfig());
        return out;
    }

    private Map<String, Object> toToolOption(ToolCallback callback) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", callback.getToolDefinition().name());
        out.put("description", callback.getToolDefinition().description());
        out.put("inputSchema", callback.getToolDefinition().inputSchema());
        return out;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record ChatTurn(String role, String content) {}

    public record ChatRequest(
            Long modelConfigId,
            String systemPrompt,
            Double temperature,
            Double topP,
            Integer maxTokens,
            Boolean useAllMcp,
            List<String> toolNames,
            List<ChatTurn> messages
    ) {
        public ChatRequest {
            toolNames = toolNames == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(toolNames.stream().filter(Objects::nonNull).toList()));
            messages = messages == null ? List.of() : messages;
        }
    }
}