package io.github.flowerjvm.flower.agent.run;

/**
 * Observable state of one AgentRun.
 */
public enum AgentRunStatus {
    CREATED,
    RUNNING,
    WAITING_MODEL,
    WAITING_TOOL,
    COMPLETED,
    INTERRUPTED,
    BUDGET_EXHAUSTED,
    CANCELLED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED
                || this == INTERRUPTED
                || this == BUDGET_EXHAUSTED
                || this == CANCELLED
                || this == FAILED;
    }
}

