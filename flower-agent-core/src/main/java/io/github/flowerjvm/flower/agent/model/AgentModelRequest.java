package io.github.flowerjvm.flower.agent.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * One provider-neutral model turn request.
 */
public record AgentModelRequest(
        String runId,
        String threadId,
        int turnNumber,
        int attempt,
        String modelId,
        List<AgentMessage> messages,
        List<ToolDefinition> tools,
        Duration timeout,
        Map<String, Object> metadata
) {

    public AgentModelRequest {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must not be blank");
        }
        if (turnNumber <= 0 || attempt <= 0) {
            throw new IllegalArgumentException("turnNumber and attempt must be positive");
        }
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

