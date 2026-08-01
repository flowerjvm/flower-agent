package io.github.flowerjvm.flower.agent.gateway;

import io.github.flowerjvm.flower.agent.model.AgentModelRequest;

/**
 * Non-blocking provider port for agent model turns.
 *
 * <p>{@link #submit(AgentModelRequest)} must return promptly. Network or model
 * work continues outside the Flower worker thread and is observed through the
 * returned handle.</p>
 */
@FunctionalInterface
public interface AgentModelGateway {

    AgentModelCall submit(AgentModelRequest request);
}

