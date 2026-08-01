package io.github.flowerjvm.flower.agent.control;

/**
 * Hard budget that stopped an agent run.
 */
public enum BudgetViolation {
    TURN_LIMIT,
    TOOL_CALL_LIMIT,
    INPUT_TOKEN_LIMIT,
    OUTPUT_TOKEN_LIMIT,
    TIME_LIMIT
}

