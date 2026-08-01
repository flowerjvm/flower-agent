package io.github.flowerjvm.flower.agent.run;

import io.github.flowerjvm.flower.agent.model.AgentUsage;

import java.time.Instant;

/**
 * Snapshot of one model turn.
 */
public record AgentTurn(
        int turnNumber,
        AgentTurnStatus status,
        int attempts,
        Instant startedAt,
        Instant endedAt,
        AgentUsage usage,
        String failureMessage
) {

    public AgentTurn {
        if (turnNumber <= 0 || attempts < 0) {
            throw new IllegalArgumentException("invalid turn number or attempts");
        }
        if (status == null || startedAt == null) {
            throw new IllegalArgumentException("status and startedAt must not be null");
        }
        usage = usage == null ? AgentUsage.NONE : usage;
    }
}

