package io.github.flowerjvm.flower.agent.model;

import java.util.Map;

/**
 * One tool invocation proposed by the model.
 */
public record ToolCall(
        String callId,
        String toolName,
        Map<String, Object> arguments
) {

    public ToolCall {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}

