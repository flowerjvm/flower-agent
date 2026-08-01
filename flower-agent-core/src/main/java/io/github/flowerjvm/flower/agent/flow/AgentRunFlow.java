package io.github.flowerjvm.flower.agent.flow;

import io.github.flowerjvm.flower.agent.model.AgentMessage;
import io.github.flowerjvm.flower.agent.run.AgentRun;
import io.github.flowerjvm.flower.agent.run.AgentThread;
import io.github.flowerjvm.flower.core.flow.Flow;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Handle exposing a Flower Flow and its current AgentRun snapshot.
 */
public final class AgentRunFlow {

    private final Flow flow;
    private final AgentRunSession session;
    private final Clock clock;

    AgentRunFlow(Flow flow, AgentRunSession session, Clock clock) {
        this.flow = Objects.requireNonNull(flow, "flow must not be null");
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public Flow flow() {
        return flow;
    }

    public AgentRun run() {
        return session.snapshot();
    }

    public AgentThread thread() {
        return session.thread();
    }

    public List<AgentMessage> transcript() {
        return session.transcriptStore().messages(thread().threadId());
    }

    public void cancel(String reason) {
        session.cancel(reason, clock.instant());
        flow.cancel();
    }
}
