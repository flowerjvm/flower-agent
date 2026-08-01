package io.github.flowerjvm.flower.agent.control;

import java.time.Duration;

/**
 * Retry policy for failure of the current model turn.
 *
 * <p>This does not retry a whole AI Harness task or a business action.</p>
 */
@FunctionalInterface
public interface ModelTurnRetryPolicy {

    ModelTurnRetryDecision decide(int completedAttempts, Throwable failure);

    static ModelTurnRetryPolicy noRetry() {
        return (attempts, failure) -> ModelTurnRetryDecision.stop();
    }

    static ModelTurnRetryPolicy maxAttempts(int maxAttempts) {
        return maxAttempts(maxAttempts, Duration.ZERO);
    }

    static ModelTurnRetryPolicy maxAttempts(int maxAttempts, Duration delay) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be null or negative");
        }
        return (attempts, failure) -> attempts < maxAttempts
                ? ModelTurnRetryDecision.retryAfter(delay)
                : ModelTurnRetryDecision.stop();
    }
}
