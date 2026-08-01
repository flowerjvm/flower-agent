package io.github.flowerjvm.flower.agent.flow;

import io.github.flowerjvm.flower.core.step.Step;
import io.github.flowerjvm.flower.core.step.StepContext;
import io.github.flowerjvm.flower.core.step.StepResult;

import java.time.Instant;

final class FinalizeRunStep extends Step {

    private final AgentRunSession session;

    FinalizeRunStep(AgentRunSession session) {
        this.session = session;
    }

    @Override
    protected StepResult onTick(StepContext ctx) {
        if (!session.isTerminal()) {
            session.fail(
                    "NON_TERMINAL_FINALIZE",
                    "agent flow reached finalize without a terminal run status",
                    Instant.ofEpochMilli(ctx.clock().currentTimeMillis()));
        }
        return StepResult.finish();
    }
}

