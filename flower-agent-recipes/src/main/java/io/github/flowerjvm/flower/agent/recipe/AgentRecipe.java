package io.github.flowerjvm.flower.agent.recipe;

import io.github.flowerjvm.flower.agent.AgentSpec;
import io.github.flowerjvm.flower.agent.flow.AgentRunFlow;
import io.github.flowerjvm.flower.agent.model.AgentMessage;
import io.github.flowerjvm.flower.agent.run.AgentThread;

/**
 * Reusable definition of one Agent loop shape and its runtime dependencies.
 */
public interface AgentRecipe {

    String recipeId();

    AgentSpec spec();

    AgentRunFlow createRun(AgentThread thread, AgentMessage initialMessage);
}
