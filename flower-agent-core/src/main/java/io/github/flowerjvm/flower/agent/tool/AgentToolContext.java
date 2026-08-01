package io.github.flowerjvm.flower.agent.tool;

import java.util.Map;

/**
 * Stable identity supplied to a tool adapter.
 */
public record AgentToolContext(
        String runId,
        String threadId,
        int turnNumber,
        Map<String, Object> metadata
) {

    public AgentToolContext {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must not be blank");
        }
        if (turnNumber <= 0) {
            throw new IllegalArgumentException("turnNumber must be positive");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

