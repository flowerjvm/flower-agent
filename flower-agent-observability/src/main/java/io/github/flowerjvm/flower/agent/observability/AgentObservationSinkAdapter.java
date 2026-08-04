package io.github.flowerjvm.flower.agent.observability;

import io.github.flowerjvm.flower.agent.observation.AgentEvent;
import io.github.flowerjvm.flower.agent.observation.AgentEventSink;
import io.github.flowerjvm.flower.observability.tracing.FlowerObservationEvent;
import io.github.flowerjvm.flower.observability.tracing.FlowerObservationSink;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Maps native Agent lifecycle events to common Flower observation events.
 * The default attribute allowlist excludes failure messages and all model or
 * Tool payloads.
 */
public final class AgentObservationSinkAdapter implements AgentEventSink {

    public static final String SOURCE = "flower-agent";

    private static final Set<String> DEFAULT_ATTRIBUTES = Set.of(
            "attempt",
            "inputTokens",
            "outputTokens",
            "finishReason",
            "status",
            "attempts",
            "delayMillis",
            "toolCallNumber",
            "errorCode",
            "executionId",
            "turns",
            "toolCalls",
            "budgetViolation",
            "failureCode");

    private final FlowerObservationSink destination;
    private final AgentObservationCorrelationResolver correlationResolver;
    private final Set<String> attributeAllowlist;

    public AgentObservationSinkAdapter(FlowerObservationSink destination) {
        this(destination, AgentObservationCorrelationResolver.standalone(), DEFAULT_ATTRIBUTES);
    }

    public AgentObservationSinkAdapter(
            FlowerObservationSink destination,
            AgentObservationCorrelationResolver correlationResolver) {
        this(destination, correlationResolver, DEFAULT_ATTRIBUTES);
    }

    public AgentObservationSinkAdapter(
            FlowerObservationSink destination,
            AgentObservationCorrelationResolver correlationResolver,
            Set<String> attributeAllowlist) {
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null");
        }
        if (correlationResolver == null) {
            throw new IllegalArgumentException("correlationResolver must not be null");
        }
        this.destination = destination;
        this.correlationResolver = correlationResolver;
        this.attributeAllowlist = copyAllowlist(attributeAllowlist);
    }

    @Override
    public void publish(AgentEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        AgentObservationCorrelation correlation = correlationResolver.resolve(event);
        if (correlation == null) {
            throw new IllegalStateException("correlationResolver returned null");
        }
        destination.publish(FlowerObservationEvent.builder(SOURCE, event.type().name())
                .eventId(SOURCE + ":" + event.runId() + ":" + event.sequence())
                .traceId(correlation.traceId())
                .runId(event.runId())
                .parentRunId(correlation.parentRunId())
                .operationId(event.operationId())
                .operationName(event.operationName())
                .sequence(event.sequence())
                .occurredAt(event.occurredAt())
                .attributes(attributes(event))
                .build());
    }

    public Set<String> attributeAllowlist() {
        return attributeAllowlist;
    }

    private Map<String, Object> attributes(AgentEvent event) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("agent.recipe.id", event.recipeId());
        attributes.put("agent.id", event.agentId());
        attributes.put("agent.thread.id", event.threadId());
        attributes.put("agent.turn.number", event.turnNumber());
        for (Map.Entry<String, Object> entry : event.attributes().entrySet()) {
            if (attributeAllowlist.contains(entry.getKey()) && entry.getValue() != null) {
                attributes.put("agent." + entry.getKey(), entry.getValue());
            }
        }
        return attributes;
    }

    private static Set<String> copyAllowlist(Set<String> allowlist) {
        if (allowlist == null) {
            throw new IllegalArgumentException("attributeAllowlist must not be null");
        }
        Set<String> copy = new LinkedHashSet<>();
        for (String name : allowlist) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("attribute name must not be blank");
            }
            copy.add(name.trim());
        }
        return Collections.unmodifiableSet(copy);
    }
}
