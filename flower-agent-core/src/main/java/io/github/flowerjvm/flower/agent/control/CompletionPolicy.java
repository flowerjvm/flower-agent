package io.github.flowerjvm.flower.agent.control;

import io.github.flowerjvm.flower.agent.model.AgentModelResponse;
import io.github.flowerjvm.flower.agent.run.AgentRun;

/**
 * Decides how the agent loop routes one model response.
 *
 * <p>This policy does not validate or refine a host's final structured output.
 * A host that needs that behavior can wrap the completed agent run in an outer
 * AI Harness task.</p>
 */
@FunctionalInterface
public interface CompletionPolicy {

    CompletionDecision decide(AgentRun run, AgentModelResponse response);

    static CompletionPolicy toolCallsThenText() {
        return (run, response) -> {
            if (!response.toolCalls().isEmpty()) {
                return CompletionDecision.executeTools();
            }
            if (!response.assistantMessage().content().isBlank()) {
                return CompletionDecision.complete();
            }
            return CompletionDecision.fail("model returned neither tool calls nor a final answer");
        };
    }
}
