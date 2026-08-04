package io.github.flowerjvm.flower.agent.flow;

import io.github.flowerjvm.flower.agent.model.AgentMessage;
import io.github.flowerjvm.flower.core.step.Step;
import io.github.flowerjvm.flower.core.step.StepContext;
import io.github.flowerjvm.flower.core.step.StepResult;

import java.time.Instant;
import java.util.List;

final class PrepareContextStep extends Step {

    private final AgentRunSession session;
    private final String finalizeStepId;

    PrepareContextStep(AgentRunSession session, String finalizeStepId) {
        this.session = session;
        this.finalizeStepId = finalizeStepId;
    }

    @Override
    protected StepResult onTick(StepContext ctx) {
        if (session.isTerminal()) {
            return StepResult.goTo(finalizeStepId);
        }
        Instant now = now(ctx);
        if (!session.beginNextTurn(now)) {
            return StepResult.goTo(finalizeStepId);
        }

        List<AgentMessage> messages;
        try {
            // ContextBuilder is a bounded in-memory selector; it must never perform I/O here.
            messages = session.spec().contextBuilder().build(
                    session.snapshot(),
                    session.thread(),
                    session.transcriptStore());
            session.prepareCurrentRequest(messages, now);
        } catch (Throwable failure) {
            session.failCurrentTurn("CONTEXT_BUILD_FAILED", failure, now);
            return StepResult.goTo(finalizeStepId);
        }
        return StepResult.done();
    }

    private static Instant now(StepContext ctx) {
        return Instant.ofEpochMilli(ctx.clock().currentTimeMillis());
    }
}
