package io.github.flowerjvm.flower.agent.control;

import java.time.Duration;

/**
 * Decision after failure of one model-turn attempt.
 */
public record ModelTurnRetryDecision(Type type, Duration delay) {

    public enum Type {
        STOP,
        RETRY
    }

    public ModelTurnRetryDecision {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        delay = delay == null ? Duration.ZERO : delay;
        if (delay.isNegative()) {
            throw new IllegalArgumentException("retry delay must not be negative");
        }
        if (type == Type.STOP && !delay.isZero()) {
            throw new IllegalArgumentException("stop decisions cannot have a retry delay");
        }
    }

    public static ModelTurnRetryDecision stop() {
        return new ModelTurnRetryDecision(Type.STOP, Duration.ZERO);
    }

    public static ModelTurnRetryDecision retryNow() {
        return retryAfter(Duration.ZERO);
    }

    public static ModelTurnRetryDecision retryAfter(Duration delay) {
        return new ModelTurnRetryDecision(Type.RETRY, delay);
    }

    public boolean shouldRetry() {
        return type == Type.RETRY;
    }
}

