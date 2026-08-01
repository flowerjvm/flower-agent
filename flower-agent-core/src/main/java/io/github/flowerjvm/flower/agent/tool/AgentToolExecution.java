package io.github.flowerjvm.flower.agent.tool;

import io.github.flowerjvm.flower.agent.model.ToolResult;

/**
 * Pollable handle for one tool invocation.
 */
public interface AgentToolExecution {

    String executionId();

    ToolExecutionStatus poll();

    ToolResult result();

    Throwable error();

    /**
     * Best-effort cancellation. Implementations must allow repeated calls.
     */
    void cancel();
}
