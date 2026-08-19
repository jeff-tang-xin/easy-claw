package com.xinl.easyclaw.agent.embabel.domain;

import java.util.List;

public record HistoryContextData(
        List<HistoryMessage> messages
) {
    public HistoryContextData {
        if (messages == null) messages = List.of();
    }

    public record HistoryMessage(String role, String content) {}
}
