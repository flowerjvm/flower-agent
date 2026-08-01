package io.github.flowerjvm.flower.agent.run;

import io.github.flowerjvm.flower.agent.control.BudgetViolation;
import io.github.flowerjvm.flower.agent.model.AgentMessage;

import java.time.Instant;
import java.util.List;

/**
 * Immutable observable snapshot of one agent execution.
 */
public record AgentRun(
        String runId,
        String agentId,
        String threadId,
        AgentRunStatus status,
        Instant startedAt,
        Instant updatedAt,
        Instant endedAt,
        List<AgentTurn> turns,
        int toolCalls,
        long inputTokens,
        long outputTokens,
        AgentMessage finalMessage,
        AgentInterrupt interrupt,
        BudgetViolation budgetViolation,
        String failureCode,
        String failureMessage
) {

    public AgentRun {
        if (runId == null || runId.isBlank() || agentId == null || agentId.isBlank()
                || threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("runId, agentId, and threadId must not be blank");
        }
        if (status == null || startedAt == null || updatedAt == null) {
            throw new IllegalArgumentException("status and timestamps must not be null");
        }
        turns = turns == null ? List.of() : List.copyOf(turns);
        if (toolCalls < 0 || inputTokens < 0L || outputTokens < 0L) {
            throw new IllegalArgumentException("run counters must not be negative");
        }
    }

    public int turnCount() {
        return turns.size();
    }
}

