package io.github.flowerjvm.flower.agent.model;

import java.util.Map;

/**
 * Provider-neutral result of one agent tool call.
 */
public record ToolResult(
        String callId,
        String toolName,
        ToolResultStatus status,
        String content,
        String errorCode,
        String resumeToken,
        Map<String, Object> metadata
) {

    public ToolResult {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        content = content == null ? "" : content;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (status == ToolResultStatus.INTERRUPTED && (resumeToken == null || resumeToken.isBlank())) {
            throw new IllegalArgumentException("interrupted tool results require a resumeToken");
        }
    }

    public static ToolResult succeeded(String callId, String toolName, String content) {
        return new ToolResult(callId, toolName, ToolResultStatus.SUCCEEDED, content, null, null, Map.of());
    }

    public static ToolResult failed(String callId, String toolName, String code, String message) {
        return new ToolResult(callId, toolName, ToolResultStatus.FAILED, message, code, null, Map.of());
    }

    public static ToolResult cancelled(String callId, String toolName, String code, String message) {
        return new ToolResult(callId, toolName, ToolResultStatus.CANCELLED, message, code, null, Map.of());
    }

    public static ToolResult interrupted(
            String callId,
            String toolName,
            String message,
            String resumeToken,
            Map<String, Object> metadata
    ) {
        return new ToolResult(
                callId,
                toolName,
                ToolResultStatus.INTERRUPTED,
                message,
                null,
                resumeToken,
                metadata);
    }
}
