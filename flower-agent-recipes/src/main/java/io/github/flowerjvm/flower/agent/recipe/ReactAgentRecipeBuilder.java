package io.github.flowerjvm.flower.agent.recipe;

import io.github.flowerjvm.flower.agent.AgentSpec;
import io.github.flowerjvm.flower.agent.flow.AgentRunFlow;
import io.github.flowerjvm.flower.agent.flow.AgentRunFlowFactory;
import io.github.flowerjvm.flower.agent.gateway.AgentModelGateway;
import io.github.flowerjvm.flower.agent.model.AgentMessage;
import io.github.flowerjvm.flower.agent.observation.AgentEventSink;
import io.github.flowerjvm.flower.agent.run.AgentThread;
import io.github.flowerjvm.flower.agent.tool.AgentTool;
import io.github.flowerjvm.flower.agent.tool.InMemoryToolRegistry;
import io.github.flowerjvm.flower.agent.tool.ToolRegistry;
import io.github.flowerjvm.flower.agent.transcript.TranscriptStore;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Fluent configuration for the built-in ReAct Agent recipe.
 */
public final class ReactAgentRecipeBuilder {

    private final AgentSpec spec;
    private AgentModelGateway modelGateway;
    private ToolRegistry toolRegistry = ToolRegistry.empty();
    private TranscriptStore transcriptStore;
    private AgentEventSink eventSink = AgentEventSink.noop();
    private Clock clock = Clock.systemUTC();

    ReactAgentRecipeBuilder(AgentSpec spec) {
        this.spec = Objects.requireNonNull(spec, "spec must not be null");
    }

    public ReactAgentRecipeBuilder modelGateway(AgentModelGateway modelGateway) {
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway must not be null");
        return this;
    }

    public ReactAgentRecipeBuilder tools(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry == null ? ToolRegistry.empty() : toolRegistry;
        return this;
    }

    public ReactAgentRecipeBuilder tools(AgentTool... tools) {
        if (tools == null) {
            this.toolRegistry = ToolRegistry.empty();
            return this;
        }
        List<AgentTool> selected = Arrays.asList(tools.clone());
        this.toolRegistry = new InMemoryToolRegistry(selected);
        return this;
    }

    public ReactAgentRecipeBuilder transcripts(TranscriptStore transcriptStore) {
        this.transcriptStore = Objects.requireNonNull(transcriptStore, "transcriptStore must not be null");
        return this;
    }

    public ReactAgentRecipeBuilder events(AgentEventSink eventSink) {
        this.eventSink = eventSink == null ? AgentEventSink.noop() : eventSink;
        return this;
    }

    public ReactAgentRecipeBuilder clock(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        return this;
    }

    public AgentRecipe build() {
        if (modelGateway == null) {
            throw new IllegalStateException("modelGateway must be configured");
        }
        if (transcriptStore == null) {
            throw new IllegalStateException("transcriptStore must be configured");
        }
        AgentRunFlowFactory factory = new AgentRunFlowFactory(
                modelGateway,
                toolRegistry,
                transcriptStore,
                clock,
                eventSink);
        return new ReactAgentRecipe(spec, factory);
    }

    private record ReactAgentRecipe(AgentSpec spec, AgentRunFlowFactory factory) implements AgentRecipe {

        @Override
        public String recipeId() {
            return AgentRunFlowFactory.RECIPE_ID;
        }

        @Override
        public AgentRunFlow createRun(AgentThread thread, AgentMessage initialMessage) {
            return factory.createFlow(spec, thread, initialMessage);
        }
    }
}
