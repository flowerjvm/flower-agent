package io.github.flowerjvm.flower.agent.flow;

import io.github.flowerjvm.flower.agent.control.ModelTurnRetryDecision;
import io.github.flowerjvm.flower.agent.gateway.AgentModelCall;
import io.github.flowerjvm.flower.agent.gateway.AgentModelCallStatus;
import io.github.flowerjvm.flower.agent.gateway.AgentModelGateway;
import io.github.flowerjvm.flower.agent.model.AgentModelResponse;
import io.github.flowerjvm.flower.core.step.Step;
import io.github.flowerjvm.flower.core.step.StepContext;
import io.github.flowerjvm.flower.core.step.StepResult;

import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

final class AwaitModelTurnStep extends Step {

    private final AgentRunSession session;
    private final AgentModelGateway gateway;
    private final String finalizeStepId;

    private AgentModelCall call;
    private boolean completed;
    private boolean retryBackoff;

    AwaitModelTurnStep(AgentRunSession session, AgentModelGateway gateway, String finalizeStepId) {
        this.session = session;
        this.gateway = gateway;
        this.finalizeStepId = finalizeStepId;
    }

    @Override
    protected void onEnter(StepContext ctx) {
        completed = false;
        retryBackoff = false;
        ctx.startTimeout(session.spec().modelTimeout().toMillis());
    }

    @Override
    protected StepResult onTick(StepContext ctx) {
        if (session.isTerminal()) {
            return StepResult.goTo(finalizeStepId);
        }
        if (retryBackoff) {
            if (!ctx.timedOut()) {
                return StepResult.stay();
            }
            retryBackoff = false;
            ctx.startTimeout(session.spec().modelTimeout().toMillis());
        }
        if (call == null) {
            try {
                call = gateway.submit(session.currentRequest());
                if (call == null) {
                    return handleFailure(ctx, new IllegalStateException("model gateway returned null call"));
                }
                Instant now = now(ctx);
                session.modelCallSubmitted(call.callId(), now);
                session.markWaitingModel(now);
                return StepResult.stay();
            } catch (Throwable failure) {
                return handleFailure(ctx, failure);
            }
        }
        if (ctx.timedOut()) {
            return handleFailure(ctx, new TimeoutException("model turn timed out"));
        }

        AgentModelCallStatus callStatus;
        try {
            callStatus = call.poll();
        } catch (Throwable failure) {
            return handleFailure(ctx, failure);
        }
        if (callStatus == null) {
            return handleFailure(ctx, new IllegalStateException("model call returned null status"));
        }
        return switch (callStatus) {
            case PENDING -> StepResult.stay();
            case READY -> acceptResponse(ctx);
            case FAILED -> handleFailure(ctx, call.error());
            case CANCELLED -> handleFailure(ctx, new CancellationException("model call was cancelled"));
        };
    }

    @Override
    protected void onExit(StepContext ctx) {
        if (!completed && (call != null || retryBackoff)) {
            cancelCall();
            session.cancel("agent flow left a pending model turn", now(ctx));
        }
        call = null;
        retryBackoff = false;
    }

    @Override
    protected void onReset(StepContext ctx) {
        call = null;
        completed = false;
        retryBackoff = false;
    }

    private StepResult acceptResponse(StepContext ctx) {
        AgentModelResponse response;
        try {
            response = call.result();
            if (response == null) {
                return handleFailure(ctx, new IllegalStateException("model call returned null response"));
            }
        } catch (Throwable failure) {
            return handleFailure(ctx, failure);
        }
        session.acceptModelResponse(call.callId(), response, now(ctx));
        completed = true;
        return StepResult.done();
    }

    private StepResult handleFailure(StepContext ctx, Throwable failure) {
        Throwable actual = failure == null ? new IllegalStateException("model call failed") : failure;
        String failedCallId = callId();
        session.modelCallFailed(failedCallId, actual, now(ctx));
        cancelCall();
        if (!session.applyUsageBudget(now(ctx))) {
            completed = true;
            return StepResult.goTo(finalizeStepId);
        }

        ModelTurnRetryDecision retryDecision;
        try {
            retryDecision = session.spec().modelTurnRetryPolicy().decide(session.currentAttempt(), actual);
            if (retryDecision == null) {
                throw new IllegalStateException("modelTurnRetryPolicy returned null");
            }
        } catch (Throwable policyFailure) {
            actual.addSuppressed(policyFailure);
            retryDecision = ModelTurnRetryDecision.stop();
        }

        if (retryDecision.shouldRetry()) {
            session.prepareModelRetry(now(ctx));
            session.modelRetryScheduled(retryDecision.delay(), now(ctx));
            if (retryDecision.delay().isZero()) {
                ctx.startTimeout(session.spec().modelTimeout().toMillis());
            } else {
                retryBackoff = true;
                ctx.startTimeout(retryDecision.delay().toMillis());
            }
            return StepResult.stay();
        }
        session.failCurrentTurn("MODEL_TURN_FAILED", actual, now(ctx));
        completed = true;
        return StepResult.goTo(finalizeStepId);
    }

    private void cancelCall() {
        if (call == null) {
            return;
        }
        try {
            call.cancel();
        } catch (Throwable ignored) {
            // Cancellation is best-effort; the run still takes its explicit failure path.
        } finally {
            call = null;
        }
    }

    private String callId() {
        if (call == null) {
            return null;
        }
        try {
            return call.callId();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Instant now(StepContext ctx) {
        return Instant.ofEpochMilli(ctx.clock().currentTimeMillis());
    }
}
