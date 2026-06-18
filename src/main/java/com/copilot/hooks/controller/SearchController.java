package com.copilot.hooks.controller;

import com.copilot.hooks.security.AppPrincipal;
import com.copilot.hooks.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final VectorStore vectorStore;
    private final CurrentUser currentUser;

    @GetMapping
    public List<Map<String, Object>> search(@RequestParam String q,
                                            @RequestParam(defaultValue = "10") int topK) {
        AppPrincipal me = currentUser.require();
        var b = new FilterExpressionBuilder();
        var filter = b.eq("userId", me.userId()).build();
        SearchRequest req = SearchRequest.builder()
                .query(q)
                .topK(Math.min(Math.max(topK, 1), 50))
                .filterExpression(filter)
                .build();
        List<Document> docs = vectorStore.similaritySearch(req);
        return docs.stream().map(d -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", d.getId());
            m.put("score", d.getScore());
            m.put("content", d.getText());
            m.put("metadata", d.getMetadata());
            return m;
        }).toList();
    }
}
