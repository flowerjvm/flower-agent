package io.github.flowerjvm.flower.agent;

import io.github.flowerjvm.flower.agent.control.AgentBudget;
import io.github.flowerjvm.flower.agent.control.CompletionPolicy;
import io.github.flowerjvm.flower.agent.control.ModelTurnRetryPolicy;
import io.github.flowerjvm.flower.agent.transcript.ContextBuilder;

import java.time.Duration;
import java.util.Map;

/**
 * Stable configuration of one agent type.
 */
public record AgentSpec(
        String agentId,
        String modelId,
        String systemPrompt,
        AgentBudget budget,
        Duration modelTimeout,
        Duration toolTimeout,
        ContextBuilder contextBuilder,
        CompletionPolicy completionPolicy,
        ModelTurnRetryPolicy modelTurnRetryPolicy,
        Map<String, Object> metadata
) {

    public AgentSpec {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        budget = budget == null ? AgentBudget.defaults() : budget;
        modelTimeout = positiveOrDefault(modelTimeout, Duration.ofSeconds(60), "modelTimeout");
        toolTimeout = positiveOrDefault(toolTimeout, Duration.ofSeconds(60), "toolTimeout");
        contextBuilder = contextBuilder == null ? ContextBuilder.fullTranscript() : contextBuilder;
        completionPolicy = completionPolicy == null
                ? CompletionPolicy.toolCallsThenText()
                : completionPolicy;
        modelTurnRetryPolicy = modelTurnRetryPolicy == null
                ? ModelTurnRetryPolicy.noRetry()
                : modelTurnRetryPolicy;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static AgentSpec of(String agentId, String modelId, String systemPrompt) {
        return new AgentSpec(
                agentId,
                modelId,
                systemPrompt,
                AgentBudget.defaults(),
                Duration.ofSeconds(60),
                Duration.ofSeconds(60),
                ContextBuilder.fullTranscript(),
                CompletionPolicy.toolCallsThenText(),
                ModelTurnRetryPolicy.noRetry(),
                Map.of());
    }

    private static Duration positiveOrDefault(Duration value, Duration defaultValue, String name) {
        Duration selected = value == null ? defaultValue : value;
        if (selected.isZero() || selected.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return selected;
    }
}

