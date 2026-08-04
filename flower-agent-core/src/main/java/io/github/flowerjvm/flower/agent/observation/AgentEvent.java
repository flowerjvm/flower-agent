package io.github.flowerjvm.flower.agent.observation;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable, payload-light observation event for one AgentRun.
 *
 * <p>Turn number {@code 0} and null operation fields mean that the event is
 * scoped to the whole run. Prompt, message, tool input, and tool output bodies
 * are intentionally not part of this core event contract.
 */
public record AgentEvent(
        long sequence,
        AgentEventType type,
        String runId,
        String recipeId,
        String agentId,
        String threadId,
        Instant occurredAt,
        int turnNumber,
        String operationId,
        String operationName,
        Map<String, Object> attributes
) {

    public AgentEvent {
        if (sequence <= 0L) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        if (type == null || occurredAt == null) {
            throw new IllegalArgumentException("type and occurredAt must not be null");
        }
        requireNotBlank(runId, "runId");
        requireNotBlank(recipeId, "recipeId");
        requireNotBlank(agentId, "agentId");
        requireNotBlank(threadId, "threadId");
        if (turnNumber < 0) {
            throw new IllegalArgumentException("turnNumber must not be negative");
        }
        operationId = clean(operationId);
        operationName = clean(operationName);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String selected = value.trim();
        return selected.isEmpty() ? null : selected;
    }

    private static void requireNotBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
