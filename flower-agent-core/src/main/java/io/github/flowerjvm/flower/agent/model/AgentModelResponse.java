package io.github.flowerjvm.flower.agent.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One provider-neutral model turn response.
 */
public record AgentModelResponse(
        AgentMessage assistantMessage,
        List<ToolCall> toolCalls,
        AgentUsage usage,
        String finishReason,
        Map<String, Object> metadata
) {

    public AgentModelResponse {
        Objects.requireNonNull(assistantMessage, "assistantMessage must not be null");
        if (assistantMessage.role() != AgentRole.ASSISTANT) {
            throw new IllegalArgumentException("assistantMessage must have ASSISTANT role");
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (!assistantMessage.toolCalls().isEmpty() && !assistantMessage.toolCalls().equals(toolCalls)) {
            throw new IllegalArgumentException("assistantMessage toolCalls must match response toolCalls");
        }
        assistantMessage = assistantMessage.withToolCalls(toolCalls);
        usage = usage == null ? AgentUsage.NONE : usage;
        finishReason = finishReason == null ? "" : finishReason;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public AgentModelResponse withAssistantMessage(AgentMessage nextAssistantMessage) {
        return new AgentModelResponse(nextAssistantMessage, toolCalls, usage, finishReason, metadata);
    }
}
