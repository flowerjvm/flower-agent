package io.github.flowerjvm.flower.agent.gateway;

import io.github.flowerjvm.flower.agent.model.AgentModelResponse;

/**
 * Pollable handle for one submitted model turn.
 */
public interface AgentModelCall {

    String callId();

    AgentModelCallStatus poll();

    AgentModelResponse result();

    Throwable error();

    /**
     * Best-effort cancellation. Implementations must allow repeated calls.
     */
    void cancel();
}
