package io.github.flowerjvm.flower.agent.tool;

import io.github.flowerjvm.flower.agent.model.ToolDefinition;

import java.util.List;
import java.util.Optional;

/**
 * Registry for the tools visible to the model.
 */
public interface ToolRegistry {

    Optional<AgentTool> find(String toolName);

    List<ToolDefinition> definitions();

    static ToolRegistry empty() {
        return new InMemoryToolRegistry(List.of());
    }
}

