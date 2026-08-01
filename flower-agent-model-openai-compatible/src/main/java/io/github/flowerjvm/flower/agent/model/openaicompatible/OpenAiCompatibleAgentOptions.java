package io.github.flowerjvm.flower.agent.model.openaicompatible;

/**
 * AgentSpec metadata keys understood by {@link OpenAiCompatibleAgentModelGateway}.
 */
public final class OpenAiCompatibleAgentOptions {

    public static final String TEMPERATURE = "openAiCompatible.temperature";
    public static final String MAX_TOKENS = "openAiCompatible.maxTokens";
    public static final String MAX_COMPLETION_TOKENS = "openAiCompatible.maxCompletionTokens";
    public static final String TOP_P = "openAiCompatible.topP";
    public static final String FREQUENCY_PENALTY = "openAiCompatible.frequencyPenalty";
    public static final String PRESENCE_PENALTY = "openAiCompatible.presencePenalty";
    public static final String STOP_SEQUENCES = "openAiCompatible.stopSequences";
    public static final String TOOL_CHOICE = "openAiCompatible.toolChoice";
    public static final String PARALLEL_TOOL_CALLS = "openAiCompatible.parallelToolCalls";
    public static final String EXTRA_BODY = "openAiCompatible.extraBody";

    private OpenAiCompatibleAgentOptions() {
    }
}
