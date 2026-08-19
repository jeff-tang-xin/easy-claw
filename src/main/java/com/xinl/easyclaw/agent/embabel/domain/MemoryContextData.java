package com.xinl.easyclaw.agent.embabel.domain;

import java.util.List;

public record MemoryContextData(
        List<MemoryItem> items
) {
    public MemoryContextData {
        if (items == null) items = List.of();
    }

    public record MemoryItem(
            String content,
            String type,
            double confidence,
            List<String> topics
    ) {}
}
