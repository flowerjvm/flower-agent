package io.github.flowerjvm.flower.agent.model;

import java.util.Map;

/**
 * Model-facing description of an agent tool.
 */
public record ToolDefinition(
        String name,
        String description,
        Map<String, Object> inputSchema
) {

    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        description = description == null ? "" : description;
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }
}

