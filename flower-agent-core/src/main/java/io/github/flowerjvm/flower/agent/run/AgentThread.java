package io.github.flowerjvm.flower.agent.run;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Conversation identity shared by one or more AgentRuns.
 */
public record AgentThread(
        String threadId,
        Instant createdAt,
        Map<String, Object> metadata
) {

    public AgentThread {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must not be blank");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static AgentThread create() {
        return new AgentThread(UUID.randomUUID().toString(), Instant.now(), Map.of());
    }
}

