package io.github.flowerjvm.flower.agent.tool;

import io.github.flowerjvm.flower.agent.model.ToolDefinition;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable in-memory tool registry.
 */
public final class InMemoryToolRegistry implements ToolRegistry {

    private final Map<String, AgentTool> tools;
    private final List<ToolDefinition> definitions;

    public InMemoryToolRegistry(Collection<? extends AgentTool> tools) {
        Map<String, AgentTool> indexed = new LinkedHashMap<>();
        if (tools != null) {
            for (AgentTool tool : tools) {
                if (tool == null) {
                    throw new IllegalArgumentException("tools must not contain null");
                }
                String name = tool.definition().name();
                if (indexed.putIfAbsent(name, tool) != null) {
                    throw new IllegalArgumentException("duplicate tool name: " + name);
                }
            }
        }
        this.tools = Map.copyOf(indexed);
        this.definitions = indexed.values().stream().map(AgentTool::definition).toList();
    }

    @Override
    public Optional<AgentTool> find(String toolName) {
        return Optional.ofNullable(tools.get(toolName));
    }

    @Override
    public List<ToolDefinition> definitions() {
        return definitions;
    }
}

