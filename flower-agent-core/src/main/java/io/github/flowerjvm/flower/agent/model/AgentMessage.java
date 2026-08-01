package io.github.flowerjvm.flower.agent.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Provider-neutral transcript message.
 */
public record AgentMessage(
        String messageId,
        AgentRole role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId,
        String toolName,
        Instant createdAt,
        Map<String, Object> metadata
) {

    public AgentMessage {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        Objects.requireNonNull(role, "role must not be null");
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (role != AgentRole.ASSISTANT && !toolCalls.isEmpty()) {
            throw new IllegalArgumentException("only assistant messages may declare toolCalls");
        }
        if (role == AgentRole.TOOL
                && (toolCallId == null || toolCallId.isBlank() || toolName == null || toolName.isBlank())) {
            throw new IllegalArgumentException("tool messages require toolCallId and toolName");
        }
    }

    public static AgentMessage system(String content) {
        return system(content, Instant.now());
    }

    public static AgentMessage system(String content, Instant createdAt) {
        return create(AgentRole.SYSTEM, content, List.of(), null, null, createdAt, Map.of());
    }

    public static AgentMessage user(String content) {
        return user(content, Instant.now());
    }

    public static AgentMessage user(String content, Instant createdAt) {
        return create(AgentRole.USER, content, List.of(), null, null, createdAt, Map.of());
    }

    public static AgentMessage assistant(String content) {
        return assistant(content, List.of(), Instant.now());
    }

    public static AgentMessage assistant(String content, Instant createdAt) {
        return assistant(content, List.of(), createdAt);
    }

    public static AgentMessage assistant(String content, List<ToolCall> toolCalls, Instant createdAt) {
        return create(AgentRole.ASSISTANT, content, toolCalls, null, null, createdAt, Map.of());
    }

    public static AgentMessage tool(ToolResult result) {
        return tool(result, Instant.now());
    }

    public static AgentMessage tool(ToolResult result, Instant createdAt) {
        Objects.requireNonNull(result, "result must not be null");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("status", result.status().name());
        if (result.errorCode() != null && !result.errorCode().isBlank()) {
            metadata.put("errorCode", result.errorCode());
        }
        if (!result.metadata().isEmpty()) {
            metadata.put("result", result.metadata());
        }
        return create(
                AgentRole.TOOL,
                result.content(),
                List.of(),
                result.callId(),
                result.toolName(),
                createdAt,
                metadata);
    }

    public AgentMessage withToolCalls(List<ToolCall> nextToolCalls) {
        return new AgentMessage(
                messageId,
                role,
                content,
                nextToolCalls,
                toolCallId,
                toolName,
                createdAt,
                metadata);
    }

    public AgentMessage withCreatedAt(Instant nextCreatedAt) {
        return new AgentMessage(
                messageId,
                role,
                content,
                toolCalls,
                toolCallId,
                toolName,
                nextCreatedAt,
                metadata);
    }

    private static AgentMessage create(
            AgentRole role,
            String content,
            List<ToolCall> toolCalls,
            String toolCallId,
            String toolName,
            Instant createdAt,
            Map<String, Object> metadata
    ) {
        return new AgentMessage(
                UUID.randomUUID().toString(),
                role,
                content,
                toolCalls,
                toolCallId,
                toolName,
                createdAt,
                metadata);
    }
}
