package io.github.flowerjvm.flower.agent.control;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Hard bounds for one AgentRun.
 */
public record AgentBudget(
        int maxTurns,
        int maxToolCalls,
        long maxInputTokens,
        long maxOutputTokens,
        Duration maxDuration
) {

    public AgentBudget {
        if (maxTurns <= 0 || maxToolCalls <= 0) {
            throw new IllegalArgumentException("turn and tool-call limits must be positive");
        }
        if (maxInputTokens <= 0L || maxOutputTokens <= 0L) {
            throw new IllegalArgumentException("token limits must be positive");
        }
        if (maxDuration == null || maxDuration.isZero() || maxDuration.isNegative()) {
            throw new IllegalArgumentException("maxDuration must be positive");
        }
    }

    public static AgentBudget defaults() {
        return new AgentBudget(24, 64, 200_000L, 64_000L, Duration.ofMinutes(15));
    }

    public Optional<BudgetViolation> beforeTurn(
            int turnsStarted,
            long inputTokens,
            long outputTokens,
            Instant startedAt,
            Instant now
    ) {
        Optional<BudgetViolation> common = commonViolation(inputTokens, outputTokens, startedAt, now);
        if (common.isPresent()) {
            return common;
        }
        return turnsStarted >= maxTurns
                ? Optional.of(BudgetViolation.TURN_LIMIT)
                : Optional.empty();
    }

    public Optional<BudgetViolation> beforeTool(
            int toolCallsStarted,
            long inputTokens,
            long outputTokens,
            Instant startedAt,
            Instant now
    ) {
        Optional<BudgetViolation> common = commonViolation(inputTokens, outputTokens, startedAt, now);
        if (common.isPresent()) {
            return common;
        }
        return toolCallsStarted >= maxToolCalls
                ? Optional.of(BudgetViolation.TOOL_CALL_LIMIT)
                : Optional.empty();
    }

    public Optional<BudgetViolation> afterUsage(
            long inputTokens,
            long outputTokens,
            Instant startedAt,
            Instant now
    ) {
        return commonViolation(inputTokens, outputTokens, startedAt, now);
    }

    private Optional<BudgetViolation> commonViolation(
            long inputTokens,
            long outputTokens,
            Instant startedAt,
            Instant now
    ) {
        if (inputTokens > maxInputTokens) {
            return Optional.of(BudgetViolation.INPUT_TOKEN_LIMIT);
        }
        if (outputTokens > maxOutputTokens) {
            return Optional.of(BudgetViolation.OUTPUT_TOKEN_LIMIT);
        }
        if (!now.isBefore(startedAt.plus(maxDuration))) {
            return Optional.of(BudgetViolation.TIME_LIMIT);
        }
        return Optional.empty();
    }
}

