package com.xinl.easyclaw.agent.embabel.domain;

import java.util.List;

public record ChatResult(
        String reply,
        List<String> steps,
        String modelUsed
) {
}
