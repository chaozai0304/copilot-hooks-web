package com.copilot.hooks.controller;

import com.copilot.hooks.service.ChatWorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatWorkspaceService chatWorkspaceService;

    @GetMapping("/options")
    public Map<String, Object> options() {
        return chatWorkspaceService.options();
    }

    @PostMapping("/completions")
    public Map<String, Object> complete(@RequestBody ChatWorkspaceService.ChatRequest request) {
        return chatWorkspaceService.chat(request);
    }
}