package io.github.flowerjvm.flower.agent.flow;

import io.github.flowerjvm.flower.agent.AgentSpec;
import io.github.flowerjvm.flower.agent.gateway.AgentModelGateway;
import io.github.flowerjvm.flower.agent.model.AgentMessage;
import io.github.flowerjvm.flower.agent.model.AgentRole;
import io.github.flowerjvm.flower.agent.observation.AgentEventSink;
import io.github.flowerjvm.flower.agent.run.AgentThread;
import io.github.flowerjvm.flower.agent.tool.ToolRegistry;
import io.github.flowerjvm.flower.agent.transcript.TranscriptStore;
import io.github.flowerjvm.flower.core.context.ExecutionContext;
import io.github.flowerjvm.flower.core.flow.Flow;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Low-level factory for one transient ReAct AgentRun Flow.
 *
 * <p>Most hosts should configure it through
 * {@link io.github.flowerjvm.flower.agent.recipe.AgentFlows#react(AgentSpec)}.
 */
public final class AgentRunFlowFactory {

    public static final String RECIPE_ID = "react";
    public static final String FLOW_TYPE = "flower-agent-run";
    public static final String INITIALIZE_RUN_STEP = "initialize-run";
    public static final String PREPARE_CONTEXT_STEP = "prepare-context";
    public static final String AWAIT_MODEL_TURN_STEP = "await-model-turn";
    public static final String INTERPRET_DECISION_STEP = "interpret-decision";
    public static final String EXECUTE_TOOLS_STEP = "execute-tools";
    public static final String FINALIZE_RUN_STEP = "finalize-run";

    private final AgentModelGateway gateway;
    private final ToolRegistry toolRegistry;
    private final TranscriptStore transcriptStore;
    private final Clock clock;
    private final AgentEventSink eventSink;

    public AgentRunFlowFactory(
            AgentModelGateway gateway,
            ToolRegistry toolRegistry,
            TranscriptStore transcriptStore,
            Clock clock,
            AgentEventSink eventSink
    ) {
        this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
        this.toolRegistry = toolRegistry == null ? ToolRegistry.empty() : toolRegistry;
        this.transcriptStore = Objects.requireNonNull(transcriptStore, "transcriptStore must not be null");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.eventSink = eventSink == null ? AgentEventSink.noop() : eventSink;
    }

    public AgentRunFlowFactory(
            AgentModelGateway gateway,
            ToolRegistry toolRegistry,
            TranscriptStore transcriptStore,
            Clock clock
    ) {
        this(gateway, toolRegistry, transcriptStore, clock, AgentEventSink.noop());
    }

    public AgentRunFlowFactory(
            AgentModelGateway gateway,
            ToolRegistry toolRegistry,
            TranscriptStore transcriptStore
    ) {
        this(gateway, toolRegistry, transcriptStore, Clock.systemUTC(), AgentEventSink.noop());
    }

    public AgentRunFlow createFlow(AgentSpec spec, AgentThread thread, AgentMessage initialMessage) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(thread, "thread must not be null");
        Objects.requireNonNull(initialMessage, "initialMessage must not be null");
        if (initialMessage.role() != AgentRole.USER) {
            throw new IllegalArgumentException("initialMessage must have USER role");
        }

        Instant startedAt = clock.instant();
        AgentRunSession session = new AgentRunSession(
                spec,
                thread,
                transcriptStore,
                toolRegistry,
                initialMessage,
                startedAt,
                RECIPE_ID,
                eventSink);
        Flow flow = Flow.builder(FLOW_TYPE, session.runId())
                .definitionVersion("1")
                .executionContext(ExecutionContext.builder()
                        .runId(session.runId())
                        .correlationId(thread.threadId())
                        .build())
                .step(INITIALIZE_RUN_STEP, new InitializeRunStep(session))
                .step(PREPARE_CONTEXT_STEP, new PrepareContextStep(session, FINALIZE_RUN_STEP))
                .step(AWAIT_MODEL_TURN_STEP, new AwaitModelTurnStep(session, gateway, FINALIZE_RUN_STEP))
                .step(
                        INTERPRET_DECISION_STEP,
                        new InterpretDecisionStep(session, EXECUTE_TOOLS_STEP, FINALIZE_RUN_STEP))
                .step(
                        EXECUTE_TOOLS_STEP,
                        new ExecuteToolsStep(session, toolRegistry, PREPARE_CONTEXT_STEP, FINALIZE_RUN_STEP))
                .step(FINALIZE_RUN_STEP, new FinalizeRunStep(session))
                .build();
        return new AgentRunFlow(flow, session, clock);
    }
}
