package io.github.flowerjvm.flower.agent.observation;

/**
 * Non-blocking destination for Agent observation events.
 *
 * <p>The loop may invoke this contract from a Flower Worker tick or from an
 * external cancellation thread. Implementations must return immediately,
 * remain thread-safe, and enqueue any I/O elsewhere. Sink failures are ignored
 * by the Agent runtime and must not change run behavior.
 */
@FunctionalInterface
public interface AgentEventSink {

    void publish(AgentEvent event);

    static AgentEventSink noop() {
        return event -> {
        };
    }
}
