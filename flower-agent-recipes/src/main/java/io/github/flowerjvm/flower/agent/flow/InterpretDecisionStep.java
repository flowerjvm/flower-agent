package io.github.flowerjvm.flower.agent.flow;

import io.github.flowerjvm.flower.agent.control.CompletionDecision;
import io.github.flowerjvm.flower.agent.model.AgentModelResponse;
import io.github.flowerjvm.flower.core.step.Step;
import io.github.flowerjvm.flower.core.step.StepContext;
import io.github.flowerjvm.flower.core.step.StepResult;

import java.time.Instant;
import java.util.Map;

final class InterpretDecisionStep extends Step {

    private final AgentRunSession session;
    private final String executeToolsStepId;
    private final String finalizeStepId;

    InterpretDecisionStep(AgentRunSession session, String executeToolsStepId, String finalizeStepId) {
        this.session = session;
        this.executeToolsStepId = executeToolsStepId;
        this.finalizeStepId = finalizeStepId;
    }

    @Override
    protected StepResult onTick(StepContext ctx) {
        Instant now = now(ctx);
        AgentModelResponse response = session.currentResponse();
        session.transcriptStore().append(session.thread().threadId(), response.assistantMessage());
        if (!session.applyUsageBudget(now)) {
            return StepResult.goTo(finalizeStepId);
        }

        CompletionDecision decision;
        try {
            decision = session.spec().completionPolicy().decide(session.snapshot(), response);
            if (decision == null) {
                throw new IllegalStateException("completion policy returned null");
            }
        } catch (Throwable failure) {
            session.fail("COMPLETION_POLICY_FAILED", failure.getMessage(), now);
            return StepResult.goTo(finalizeStepId);
        }

        return switch (decision.type()) {
            case EXECUTE_TOOLS -> beginTools(response, now);
            case COMPLETE -> {
                session.complete(response.assistantMessage(), now);
                yield StepResult.goTo(finalizeStepId);
            }
            case INTERRUPT -> {
                session.interrupt(decision.reason(), decision.resumeToken(), Map.of(), now);
                yield StepResult.goTo(finalizeStepId);
            }
            case FAIL -> {
                session.fail("COMPLETION_REJECTED", decision.reason(), now);
                yield StepResult.goTo(finalizeStepId);
            }
        };
    }

    private StepResult beginTools(AgentModelResponse response, Instant now) {
        if (response.toolCalls().isEmpty()) {
            session.fail("INVALID_COMPLETION_DECISION", "completion policy requested tools without tool calls", now);
            return StepResult.goTo(finalizeStepId);
        }
        session.beginToolBatch(now);
        return StepResult.goTo(executeToolsStepId);
    }

    private static Instant now(StepContext ctx) {
        return Instant.ofEpochMilli(ctx.clock().currentTimeMillis());
    }
}
