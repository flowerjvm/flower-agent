package io.github.flowerjvm.flower.agent.model.openaicompatible;

import io.github.flowerjvm.flower.agent.gateway.AgentModelCall;
import io.github.flowerjvm.flower.agent.gateway.AgentModelCallStatus;
import io.github.flowerjvm.flower.agent.model.AgentMessage;
import io.github.flowerjvm.flower.agent.model.AgentModelRequest;
import io.github.flowerjvm.flower.agent.model.AgentModelResponse;
import io.github.flowerjvm.flower.agent.model.ToolCall;
import io.github.flowerjvm.flower.agent.model.ToolDefinition;
import io.github.flowerjvm.flower.agent.model.ToolResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleAgentLiveIT {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);

    @Test
    void callsRealEndpointThroughToolRequestAndFinalAnswer() throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean("flower.agent.liveOpenAiCompatible"),
                "live profile is not enabled");
        String apiKey = environment("OPENAI_API_KEY");
        Assumptions.assumeTrue(apiKey != null, "OPENAI_API_KEY is not configured");

        String baseUrl = environmentOrDefault("OPENAI_BASE_URL", "https://api.openai.com/v1");
        String model = environmentOrDefault("OPENAI_MODEL", "gpt-4.1-mini");
        OpenAiCompatibleAgentModelGateway gateway = new OpenAiCompatibleAgentModelGateway(
                OpenAiCompatibleAgentGatewayConfig.builder(baseUrl).apiKey(apiKey).build());
        ToolDefinition echo = new ToolDefinition(
                "live.echo",
                "Echo a short message for an integration test",
                Map.of(
                        "type", "object",
                        "properties", Map.of("message", Map.of("type", "string")),
                        "required", List.of("message"),
                        "additionalProperties", false));
        String runId = "live-" + UUID.randomUUID();
        String threadId = "live-thread-" + UUID.randomUUID();
        AgentMessage system = AgentMessage.system(
                "You are an integration test. Call live.echo once. After its result, reply LIVE_SMOKE_OK.",
                Instant.now());
        AgentMessage user = AgentMessage.user("Echo the word flower.", Instant.now());
        AgentModelRequest firstRequest = request(
                runId,
                threadId,
                1,
                model,
                List.of(system, user),
                List.of(echo),
                Map.of(
                        OpenAiCompatibleAgentOptions.MAX_TOKENS, 128,
                        OpenAiCompatibleAgentOptions.TOOL_CHOICE, Map.of(
                                "type", "function",
                                "function", Map.of("name", "live.echo"))));

        AgentModelCall firstCall = gateway.submit(firstRequest);

        assertReady(firstCall, awaitTerminal(firstCall));
        AgentModelResponse firstResponse = firstCall.result();
        assertThat(firstResponse.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.toolName()).isEqualTo("live.echo");
            assertThat(call.arguments()).containsKey("message");
        });

        ToolCall toolCall = firstResponse.toolCalls().get(0);
        AgentMessage toolResult = AgentMessage.tool(
                ToolResult.succeeded(toolCall.callId(), toolCall.toolName(), "flower"),
                Instant.now());
        AgentModelRequest secondRequest = request(
                runId,
                threadId,
                2,
                model,
                List.of(system, user, firstResponse.assistantMessage(), toolResult),
                List.of(echo),
                Map.of(
                        OpenAiCompatibleAgentOptions.MAX_TOKENS, 128,
                        OpenAiCompatibleAgentOptions.TOOL_CHOICE, "none"));

        AgentModelCall secondCall = gateway.submit(secondRequest);

        assertReady(secondCall, awaitTerminal(secondCall));
        assertThat(secondCall.result().toolCalls()).isEmpty();
        assertThat(secondCall.result().assistantMessage().content()).contains("LIVE_SMOKE_OK");
        assertThat(firstResponse.usage().inputTokens() + secondCall.result().usage().inputTokens())
                .isPositive();
    }

    private static AgentModelRequest request(
            String runId,
            String threadId,
            int turn,
            String model,
            List<AgentMessage> messages,
            List<ToolDefinition> tools,
            Map<String, Object> metadata
    ) {
        return new AgentModelRequest(
                runId,
                threadId,
                turn,
                1,
                model,
                messages,
                tools,
                REQUEST_TIMEOUT,
                metadata);
    }

    private static AgentModelCallStatus awaitTerminal(AgentModelCall call) throws Exception {
        ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
        CompletableFuture<AgentModelCallStatus> terminal = new CompletableFuture<>();
        try {
            poller.scheduleAtFixedRate(() -> {
                try {
                    AgentModelCallStatus status = call.poll();
                    if (status != AgentModelCallStatus.PENDING) {
                        terminal.complete(status);
                    }
                } catch (Throwable failure) {
                    terminal.completeExceptionally(failure);
                }
            }, 0L, 10L, TimeUnit.MILLISECONDS);
            return terminal.get(REQUEST_TIMEOUT.plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            poller.shutdownNow();
        }
    }

    private static void assertReady(AgentModelCall call, AgentModelCallStatus status) {
        Throwable failure = call.error();
        String diagnostic = failure == null
                ? "no provider error was reported"
                : failure.getClass().getSimpleName() + ": " + failure.getMessage();
        assertThat(status)
                .withFailMessage("live OpenAI-compatible call failed: %s", diagnostic)
                .isEqualTo(AgentModelCallStatus.READY);
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String environmentOrDefault(String name, String fallback) {
        String value = environment(name);
        return value == null ? fallback : value;
    }
}
