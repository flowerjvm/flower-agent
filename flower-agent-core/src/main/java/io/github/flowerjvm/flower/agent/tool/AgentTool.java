package io.github.flowerjvm.flower.agent.tool;

import io.github.flowerjvm.flower.agent.model.ToolCall;
import io.github.flowerjvm.flower.agent.model.ToolDefinition;

/**
 * One model-facing capability.
 *
 * <p>A mutating implementation must delegate to a governed action boundary.
 * Registering an AgentTool is not business authorization.</p>
 */
public interface AgentTool {

    ToolDefinition definition();

    /**
     * Start work and return promptly with a pollable handle.
     */
    AgentToolExecution start(ToolCall call, AgentToolContext context);
}

