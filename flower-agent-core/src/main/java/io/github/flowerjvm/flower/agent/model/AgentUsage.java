package io.github.flowerjvm.flower.agent.model;

/**
 * Token usage reported for one model turn.
 */
public record AgentUsage(long inputTokens, long outputTokens) {

    public static final AgentUsage NONE = new AgentUsage(0L, 0L);

    public AgentUsage {
        if (inputTokens < 0L || outputTokens < 0L) {
            throw new IllegalArgumentException("token usage must not be negative");
        }
    }
}

