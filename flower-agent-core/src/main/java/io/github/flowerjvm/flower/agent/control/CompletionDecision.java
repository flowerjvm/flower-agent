package io.github.flowerjvm.flower.agent.control;

/**
 * CompletionPolicy outcome for one model response.
 */
public record CompletionDecision(
        Type type,
        String reason,
        String resumeToken
) {

    public enum Type {
        EXECUTE_TOOLS,
        COMPLETE,
        INTERRUPT,
        FAIL
    }

    public CompletionDecision {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        reason = reason == null ? "" : reason;
        if (type == Type.INTERRUPT && (resumeToken == null || resumeToken.isBlank())) {
            throw new IllegalArgumentException("interrupt decisions require a resumeToken");
        }
    }

    public static CompletionDecision executeTools() {
        return new CompletionDecision(Type.EXECUTE_TOOLS, "", null);
    }

    public static CompletionDecision complete() {
        return new CompletionDecision(Type.COMPLETE, "", null);
    }

    public static CompletionDecision interrupt(String reason, String resumeToken) {
        return new CompletionDecision(Type.INTERRUPT, reason, resumeToken);
    }

    public static CompletionDecision fail(String reason) {
        return new CompletionDecision(Type.FAIL, reason, null);
    }
}

