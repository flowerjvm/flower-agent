package io.github.flowerjvm.flower.agent.observability;

import io.github.flowerjvm.flower.agent.observation.AgentEvent;
import io.github.flowerjvm.flower.agent.observation.AgentEventType;
import io.github.flowerjvm.flower.observability.tracing.FlowerObservationEvent;
import io.github.flowerjvm.flower.observability.tracing.InMemoryFlowerObservationSink;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentObservationSinkAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-04T03:04:05Z");

    @Test
    void preservesAgentCorrelationAndExcludesFailurePayloads() {
        InMemoryFlowerObservationSink sink = new InMemoryFlowerObservationSink();
        AgentObservationSinkAdapter adapter = new AgentObservationSinkAdapter(
                sink,
                event -> new AgentObservationCorrelation("outer-task-3", "harness-run-3"));
        AgentEvent source = event(Map.of(
                "attempt", 2,
                "inputTokens", 41,
                "outputTokens", 13,
                "failureCode", "MODEL_FAILED",
                "failureMessage", "secret provider failure",
                "prompt", "secret prompt",
                "toolOutput", "secret tool output"));

        adapter.publish(source);

        FlowerObservationEvent observed = sink.snapshot().get(0);
        assertThat(observed.source()).isEqualTo(AgentObservationSinkAdapter.SOURCE);
        assertThat(observed.eventId()).isEqualTo("flower-agent:agent-run-1:7");
        assertThat(observed.eventType()).isEqualTo("MODEL_CALL_FAILED");
        assertThat(observed.traceId()).isEqualTo("outer-task-3");
        assertThat(observed.runId()).isEqualTo("agent-run-1");
        assertThat(observed.parentRunId()).isEqualTo("harness-run-3");
        assertThat(observed.operationId()).isEqualTo("model-call-7");
        assertThat(observed.operationName()).isEqualTo("local:model");
        assertThat(observed.sequence()).isEqualTo(7L);
        assertThat(observed.occurredAt()).isEqualTo(NOW);
        assertThat(observed.attributes())
                .containsEntry("agent.recipe.id", "react")
                .containsEntry("agent.id", "ops-agent")
                .containsEntry("agent.thread.id", "thread-1")
                .containsEntry("agent.turn.number", 2)
                .containsEntry("agent.attempt", 2)
                .containsEntry("agent.inputTokens", 41)
                .containsEntry("agent.outputTokens", 13)
                .containsEntry("agent.failureCode", "MODEL_FAILED");
        assertThat(observed.attributes().toString())
                .doesNotContain("secret provider failure")
                .doesNotContain("secret prompt")
                .doesNotContain("secret tool output");
    }

    @Test
    void standaloneCorrelationUsesAgentRunId() {
        InMemoryFlowerObservationSink sink = new InMemoryFlowerObservationSink();
        AgentObservationSinkAdapter adapter = new AgentObservationSinkAdapter(sink);

        adapter.publish(event(Map.of()));

        assertThat(sink.snapshot().get(0).traceId()).isEqualTo("agent-run-1");
        assertThat(sink.snapshot().get(0).parentRunId()).isNull();
    }

    private static AgentEvent event(Map<String, Object> attributes) {
        return new AgentEvent(
                7L,
                AgentEventType.MODEL_CALL_FAILED,
                "agent-run-1",
                "react",
                "ops-agent",
                "thread-1",
                NOW,
                2,
                "model-call-7",
                "local:model",
                attributes);
    }
}
