package io.github.flowerjvm.flower.agent.model.openaicompatible;

import java.util.OptionalInt;

/**
 * Transport, HTTP, or protocol failure from an OpenAI-compatible endpoint.
 */
public final class OpenAiCompatibleAgentGatewayException extends RuntimeException {

    private final Integer statusCode;
    private final boolean retryable;

    OpenAiCompatibleAgentGatewayException(String message, Throwable cause, Integer statusCode, boolean retryable) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public OptionalInt statusCode() {
        return statusCode == null ? OptionalInt.empty() : OptionalInt.of(statusCode);
    }

    public boolean retryable() {
        return retryable;
    }

    static OpenAiCompatibleAgentGatewayException request(String message, Throwable cause) {
        return new OpenAiCompatibleAgentGatewayException(message, cause, null, false);
    }

    static OpenAiCompatibleAgentGatewayException transport(Throwable cause) {
        return new OpenAiCompatibleAgentGatewayException(
                "OpenAI-compatible transport failed",
                cause,
                null,
                true);
    }

    static OpenAiCompatibleAgentGatewayException http(int statusCode, String message) {
        return new OpenAiCompatibleAgentGatewayException(
                message,
                null,
                statusCode,
                statusCode == 408 || statusCode == 409 || statusCode == 429 || statusCode >= 500);
    }

    static OpenAiCompatibleAgentGatewayException protocol(String message, Throwable cause) {
        return new OpenAiCompatibleAgentGatewayException(message, cause, null, false);
    }
}
