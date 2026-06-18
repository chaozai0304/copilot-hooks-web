package com.copilot.hooks.mcp;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class McpConfig {

    /**
     * Spring AI MCP server auto-config picks up any ToolCallbackProvider bean
     * and exposes its tools via the configured SSE endpoint.
     */
    @Bean
    public ToolCallbackProvider hookMcpToolCallbacks(HookMcpTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
