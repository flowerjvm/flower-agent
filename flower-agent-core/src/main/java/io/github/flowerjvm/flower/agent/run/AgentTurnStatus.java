package io.github.flowerjvm.flower.agent.run;

/**
 * State of one model turn inside an AgentRun.
 */
public enum AgentTurnStatus {
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

