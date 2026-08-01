package io.github.flowerjvm.flower.agent.flow;

import io.github.flowerjvm.flower.agent.model.AgentMessage;
import io.github.flowerjvm.flower.core.step.Step;
import io.github.flowerjvm.flower.core.step.StepContext;
import io.github.flowerjvm.flower.core.step.StepResult;

/**
 * Applies the initial user message only after the Flow actually starts.
 */
final class InitializeRunStep extends Step {

    private final AgentRunSession session;

    InitializeRunStep(AgentRunSession session) {
        this.session = session;
    }

    @Override
    protected StepResult onTick(StepContext ctx) {
        AgentMessage initialMessage = session.takeInitialMessage();
        if (initialMessage != null) {
            session.transcriptStore().append(session.thread().threadId(), initialMessage);
        }
        return StepResult.done();
    }
}

