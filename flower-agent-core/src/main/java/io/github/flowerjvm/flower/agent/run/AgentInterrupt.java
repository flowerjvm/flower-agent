package io.github.flowerjvm.flower.agent.run;

import java.util.Map;

/**
 * Persistable reason and token for a resumable AgentRun interruption.
 */
public record AgentInterrupt(
        String reason,
        String resumeToken,
        Map<String, Object> metadata
) {

    public AgentInterrupt {
        reason = reason == null ? "" : reason;
        if (resumeToken == null || resumeToken.isBlank()) {
            throw new IllegalArgumentException("resumeToken must not be blank");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

