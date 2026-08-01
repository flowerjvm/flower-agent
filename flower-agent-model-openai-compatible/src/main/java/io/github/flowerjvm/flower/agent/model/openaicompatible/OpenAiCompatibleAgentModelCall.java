package io.github.flowerjvm.flower.agent.model.openaicompatible;

import io.github.flowerjvm.flower.agent.gateway.AgentModelCall;
import io.github.flowerjvm.flower.agent.gateway.AgentModelCallStatus;
import io.github.flowerjvm.flower.agent.model.AgentModelResponse;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

final class OpenAiCompatibleAgentModelCall implements AgentModelCall {

    private final String callId;
    private final CompletableFuture<?> transportFuture;
    private final CompletableFuture<AgentModelResponse> resultFuture;

    OpenAiCompatibleAgentModelCall(
            String callId,
            CompletableFuture<?> transportFuture,
            CompletableFuture<AgentModelResponse> resultFuture
    ) {
        this.callId = Objects.requireNonNull(callId, "callId must not be null");
        this.transportFuture = Objects.requireNonNull(transportFuture, "transportFuture must not be null");
        this.resultFuture = Objects.requireNonNull(resultFuture, "resultFuture must not be null");
    }

    @Override
    public String callId() {
        return callId;
    }

    @Override
    public AgentModelCallStatus poll() {
        if (resultFuture.isCancelled() || transportFuture.isCancelled()) {
            return AgentModelCallStatus.CANCELLED;
        }
        if (!resultFuture.isDone()) {
            return AgentModelCallStatus.PENDING;
        }
        return error() == null ? AgentModelCallStatus.READY : AgentModelCallStatus.FAILED;
    }

    @Override
    public AgentModelResponse result() {
        if (poll() != AgentModelCallStatus.READY) {
            throw new IllegalStateException("OpenAI-compatible agent call is not ready: " + callId);
        }
        AgentModelResponse result = resultFuture.getNow(null);
        if (result == null) {
            throw new IllegalStateException("OpenAI-compatible agent call completed without a result: " + callId);
        }
        return result;
    }

    @Override
    public Throwable error() {
        if (!resultFuture.isDone() || resultFuture.isCancelled()) {
            return null;
        }
        try {
            resultFuture.getNow(null);
            return null;
        } catch (CompletionException ex) {
            return unwrap(ex);
        } catch (CancellationException ex) {
            return ex;
        }
    }

    @Override
    public void cancel() {
        transportFuture.cancel(true);
        resultFuture.cancel(true);
    }

    private static Throwable unwrap(CompletionException failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
