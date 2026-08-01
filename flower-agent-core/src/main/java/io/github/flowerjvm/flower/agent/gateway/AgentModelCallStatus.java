package io.github.flowerjvm.flower.agent.gateway;

/**
 * Non-blocking model-call state.
 */
public enum AgentModelCallStatus {
    PENDING,
    READY,
    FAILED,
    CANCELLED
}

