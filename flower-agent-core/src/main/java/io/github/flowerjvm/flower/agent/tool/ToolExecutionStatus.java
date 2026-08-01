package io.github.flowerjvm.flower.agent.tool;

/**
 * Non-blocking agent-tool execution state.
 */
public enum ToolExecutionStatus {
    PENDING,
    READY,
    FAILED,
    CANCELLED
}

