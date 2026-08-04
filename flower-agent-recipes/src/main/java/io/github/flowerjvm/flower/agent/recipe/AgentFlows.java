package io.github.flowerjvm.flower.agent.recipe;

import io.github.flowerjvm.flower.agent.AgentSpec;

/**
 * Entry point for Flower-native Agent loop recipes.
 */
public final class AgentFlows {

    private AgentFlows() {
    }

    /**
     * Configure the standard model, tool, model loop implemented by the ReAct recipe.
     */
    public static ReactAgentRecipeBuilder react(AgentSpec spec) {
        return new ReactAgentRecipeBuilder(spec);
    }
}
