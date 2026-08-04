package io.github.flowerjvm.flower.agent.observation;

/**
 * Stable lifecycle points emitted by Agent loop recipes.
 */
public enum AgentEventType {
    RUN_STARTED,
    TURN_STARTED,
    MODEL_CALL_SUBMITTED,
    MODEL_CALL_COMPLETED,
    MODEL_CALL_FAILED,
    MODEL_RETRY_SCHEDULED,
    TURN_COMPLETED,
    TOOL_CALL_STARTED,
    TOOL_CALL_COMPLETED,
    RUN_COMPLETED,
    RUN_INTERRUPTED,
    RUN_FAILED,
    RUN_CANCELLED,
    RUN_BUDGET_EXHAUSTED
}
