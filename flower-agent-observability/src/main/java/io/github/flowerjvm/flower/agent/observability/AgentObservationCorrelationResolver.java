package io.github.flowerjvm.flower.agent.observability;

import io.github.flowerjvm.flower.agent.observation.AgentEvent;

/** Resolves host or outer-task correlation for an Agent run. */
@FunctionalInterface
public interface AgentObservationCorrelationResolver {

    AgentObservationCorrelation resolve(AgentEvent event);

    static AgentObservationCorrelationResolver standalone() {
        return event -> AgentObservationCorrelation.standalone(event.runId());
    }
}
