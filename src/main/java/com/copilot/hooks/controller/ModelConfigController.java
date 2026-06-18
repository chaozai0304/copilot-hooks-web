package com.copilot.hooks.controller;

import com.copilot.hooks.domain.ModelConfig;
import com.copilot.hooks.repository.ModelConfigRepository;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@RestController
@RequestMapping("/api/admin/model-configs")
@RequiredArgsConstructor
public class ModelConfigController {

    private final ModelConfigRepository repo;

    public record UpsertModelConfigRequest(
            @NotBlank String name,
            String provider,
            @NotBlank String baseUrl,
            String apiKey,
            @NotBlank String chatModel,
            @NotBlank String embeddingModel,
            Integer embeddingDimensions,
            Boolean enabled,
            Boolean defaultConfig
    ) {}

    @GetMapping
    public List<Map<String, Object>> list() {
        return repo.findAllByOrderByUpdatedAtDesc().stream().map(this::view).toList();
    }

    @PostMapping
    @Transactional
    public Map<String, Object> create(@RequestBody UpsertModelConfigRequest req) {
        ModelConfig c = new ModelConfig();
        apply(c, req);
        if (c.isDefaultConfig()) clearDefault(null);
        return view(repo.save(c));
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody UpsertModelConfigRequest req) {
        return repo.findById(id).map(c -> {
            apply(c, req);
            if (c.isDefaultConfig()) clearDefault(id);
            return ResponseEntity.ok(view(repo.save(c)));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/enable")
    @Transactional
    public ResponseEntity<?> enable(@PathVariable Long id) {
        return repo.findById(id).map(c -> { c.setEnabled(true); return ResponseEntity.ok(view(repo.save(c))); })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/disable")
    @Transactional
    public ResponseEntity<?> disable(@PathVariable Long id) {
        return repo.findById(id).map(c -> { c.setEnabled(false); return ResponseEntity.ok(view(repo.save(c))); })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/default")
    @Transactional
    public ResponseEntity<?> setDefault(@PathVariable Long id) {
        return repo.findById(id).map(c -> {
            clearDefault(id);
            c.setDefaultConfig(true);
            c.setEnabled(true);
            return ResponseEntity.ok(view(repo.save(c)));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id) {
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private void apply(ModelConfig c, UpsertModelConfigRequest req) {
        c.setName(req.name());
        c.setProvider(req.provider() == null || req.provider().isBlank() ? "OpenAI Compatible" : req.provider());
        c.setBaseUrl(req.baseUrl());
        c.setChatModel(req.chatModel());
        c.setEmbeddingModel(req.embeddingModel());
        c.setEmbeddingDimensions(req.embeddingDimensions() == null ? 1536 : req.embeddingDimensions());
        if (req.enabled() != null) c.setEnabled(req.enabled());
        if (req.defaultConfig() != null) c.setDefaultConfig(req.defaultConfig());
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            // Stored as-is for now. In production, replace with KMS/secret-manager encryption.
            c.setApiKeyCipher(req.apiKey());
        }
    }

    private void clearDefault(Long exceptId) {
        repo.findAll().forEach(c -> {
            if (exceptId == null || !c.getId().equals(exceptId)) {
                c.setDefaultConfig(false);
                repo.save(c);
            }
        });
    }

    private Map<String, Object> view(ModelConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("provider", c.getProvider());
        m.put("baseUrl", c.getBaseUrl());
        m.put("apiKeyMasked", mask(c.getApiKeyCipher()));
        m.put("hasApiKey", c.getApiKeyCipher() != null && !c.getApiKeyCipher().isBlank());
        m.put("chatModel", c.getChatModel());
        m.put("embeddingModel", c.getEmbeddingModel());
        m.put("embeddingDimensions", c.getEmbeddingDimensions());
        m.put("enabled", c.isEnabled());
        m.put("defaultConfig", c.isDefaultConfig());
        m.put("updatedAt", c.getUpdatedAt());
        return m;
    }

    private String mask(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
