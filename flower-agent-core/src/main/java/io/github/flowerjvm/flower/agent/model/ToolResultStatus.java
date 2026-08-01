package io.github.flowerjvm.flower.agent.model;

/**
 * Outcome returned to the agent tool loop.
 */
public enum ToolResultStatus {
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED
}
