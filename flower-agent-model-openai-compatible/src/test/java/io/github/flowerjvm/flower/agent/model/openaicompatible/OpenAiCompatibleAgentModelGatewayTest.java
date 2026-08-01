package io.github.flowerjvm.flower.agent.model.openaicompatible;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flowerjvm.flower.agent.AgentSpec;
import io.github.flowerjvm.flower.agent.control.AgentBudget;
import io.github.flowerjvm.flower.agent.control.CompletionPolicy;
import io.github.flowerjvm.flower.agent.control.ModelTurnRetryPolicy;
import io.github.flowerjvm.flower.agent.flow.AgentRunFlow;
import io.github.flowerjvm.flower.agent.flow.AgentRunFlowFactory;
import io.github.flowerjvm.flower.agent.gateway.AgentModelCall;
import io.github.flowerjvm.flower.agent.gateway.AgentModelCallStatus;
import io.github.flowerjvm.flower.agent.model.AgentMessage;
import io.github.flowerjvm.flower.agent.model.AgentModelRequest;
import io.github.flowerjvm.flower.agent.model.ToolCall;
import io.github.flowerjvm.flower.agent.model.ToolDefinition;
import io.github.flowerjvm.flower.agent.model.ToolResult;
import io.github.flowerjvm.flower.agent.run.AgentRunStatus;
import io.github.flowerjvm.flower.agent.run.AgentThread;
import io.github.flowerjvm.flower.agent.tool.AgentTool;
import io.github.flowerjvm.flower.agent.tool.AgentToolContext;
import io.github.flowerjvm.flower.agent.tool.AgentToolExecution;
import io.github.flowerjvm.flower.agent.tool.InMemoryToolRegistry;
import io.github.flowerjvm.flower.agent.tool.ToolExecutionStatus;
import io.github.flowerjvm.flower.agent.transcript.ContextBuilder;
import io.github.flowerjvm.flower.agent.transcript.InMemoryTranscriptStore;
import io.github.flowerjvm.flower.testkit.FlowTestHarness;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleAgentModelGatewayTest {

    private static final Instant TEST_INSTANT = Instant.parse("2026-08-01T00:00:00Z");
    private static final Clock TEST_CLOCK = Clock.fixed(TEST_INSTANT, ZoneOffset.UTC);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void runsToolLoopAndPreservesOpenAiToolProtocol() throws Exception {
        QueueTransport transport = new QueueTransport(
                response(200, toolCallResponse()),
                response(200, finalResponse()));
        OpenAiCompatibleAgentModelGateway gateway = gateway(transport);
        AgentTool searchTool = new ImmediateTool(
                new ToolDefinition(
                        "atcss.log.search",
                        "Search ATCSS logs",
                        Map.of(
                                "type", "object",
                                "properties", Map.of("equipmentId", Map.of("type", "string")),
                                "required", List.of("equipmentId"))),
                "{\"severity\":\"HIGH\"}");
        AgentSpec spec = new AgentSpec(
                "incident-agent",
                "qwen-local",
                "Investigate equipment incidents.",
                AgentBudget.defaults(),
                Duration.ofSeconds(10),
                Duration.ofSeconds(10),
                ContextBuilder.fullTranscript(),
                CompletionPolicy.toolCallsThenText(),
                ModelTurnRetryPolicy.noRetry(),
                Map.of(
                        OpenAiCompatibleAgentOptions.TEMPERATURE, 0.2,
                        OpenAiCompatibleAgentOptions.MAX_TOKENS, 256,
                        OpenAiCompatibleAgentOptions.EXTRA_BODY, Map.of("seed", 7)));
        AgentRunFlow run = new AgentRunFlowFactory(
                gateway,
                new InMemoryToolRegistry(List.of(searchTool)),
                new InMemoryTranscriptStore(),
                TEST_CLOCK)
                .createFlow(
                        spec,
                        new AgentThread("thread-1", TEST_INSTANT, Map.of()),
                        AgentMessage.user("Check ARMG214.", TEST_INSTANT));

        try (FlowTestHarness harness = FlowTestHarness.create()) {
            harness.submit(run.flow()).ticks(24);
        }

        assertThat(run.run().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(run.run().finalMessage().content()).isEqualTo("ARMG214 has a high-severity incident.");
        assertThat(run.run().inputTokens()).isEqualTo(21);
        assertThat(run.run().outputTokens()).isEqualTo(8);
        assertThat(transport.requests).hasSize(2);

        JsonNode first = requestJson(transport.requests.get(0));
        assertThat(transport.requests.get(0).uri().getPath()).isEqualTo("/v1/chat/completions");
        assertThat(transport.requests.get(0).headers().firstValue("Authorization"))
                .contains("Bearer test-key");
        assertThat(transport.requests.get(0).headers().firstValue("X-Tenant"))
                .contains("terminal-a");
        assertThat(first.path("model").asText()).isEqualTo("qwen-local");
        assertThat(first.path("stream").asBoolean()).isFalse();
        assertThat(first.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(first.path("max_tokens").asInt()).isEqualTo(256);
        assertThat(first.path("seed").asInt()).isEqualTo(7);
        assertThat(first.path("messages").path(0).path("role").asText()).isEqualTo("system");
        assertThat(first.path("messages").path(1).path("role").asText()).isEqualTo("user");
        String providerToolName = first.path("tools").path(0).path("function").path("name").asText();
        assertThat(providerToolName)
                .matches("^[a-zA-Z0-9_-]+$")
                .isNotEqualTo("atcss.log.search");

        JsonNode second = requestJson(transport.requests.get(1));
        assertThat(second.path("messages")).hasSize(4);
        JsonNode assistant = second.path("messages").path(2);
        assertThat(assistant.path("role").asText()).isEqualTo("assistant");
        assertThat(assistant.path("content").isNull()).isTrue();
        assertThat(assistant.path("tool_calls").path(0).path("id").asText()).isEqualTo("call-search-1");
        assertThat(assistant.path("tool_calls").path(0).path("function").path("name").asText())
                .isEqualTo(providerToolName);
        assertThat(objectMapper.readTree(
                assistant.path("tool_calls").path(0).path("function").path("arguments").asText())
                .path("equipmentId").asText()).isEqualTo("ARMG214");
        JsonNode tool = second.path("messages").path(3);
        assertThat(tool.path("role").asText()).isEqualTo("tool");
        assertThat(tool.path("tool_call_id").asText()).isEqualTo("call-search-1");
        assertThat(tool.path("content").asText()).isEqualTo("{\"severity\":\"HIGH\"}");
    }

    @Test
    void acceptsObjectArgumentsAndGeneratesMissingProviderCallId() {
        QueueTransport transport = new QueueTransport(response(200, """
                {
                  "id": "chatcmpl-no-call-id",
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [{
                        "type": "function",
                        "function": {
                          "name": "lookup",
                          "arguments": { "id": "A-1" }
                        }
                      }, {
                        "id": "call-second",
                        "type": "function",
                        "function": {
                          "name": "lookup",
                          "arguments": "{\\"id\\":\\"A-2\\"}"
                        }
                      }]
                    },
                    "finish_reason": "tool_calls"
                  }]
                }
                """));

        AgentModelCall call = gateway(transport).submit(request(List.of(user()), List.of(toolDefinition()), Map.of()));

        assertThat(call.poll()).isEqualTo(AgentModelCallStatus.READY);
        assertThat(call.result().toolCalls()).hasSize(2);
        assertThat(call.result().toolCalls().get(0)).satisfies(toolCall -> {
            assertThat(toolCall.callId()).isEqualTo("call_chatcmpl-no-call-id_0");
            assertThat(toolCall.arguments()).containsEntry("id", "A-1");
        });
        assertThat(call.result().toolCalls().get(1)).satisfies(toolCall -> {
            assertThat(toolCall.callId()).isEqualTo("call-second");
            assertThat(toolCall.arguments()).containsEntry("id", "A-2");
        });
        assertThat(call.result().metadata()).containsEntry("generatedToolCallIds", true);
    }

    @Test
    void rejectsDanglingToolCallsBeforeDispatch() {
        QueueTransport transport = new QueueTransport(response(200, finalResponse()));
        ToolCall dangling = new ToolCall("call-1", "lookup", Map.of());
        List<AgentMessage> messages = List.of(
                user(),
                AgentMessage.assistant("", List.of(dangling), TEST_INSTANT));

        AgentModelCall call = gateway(transport).submit(request(messages, List.of(toolDefinition()), Map.of()));

        assertThat(call.poll()).isEqualTo(AgentModelCallStatus.FAILED);
        assertThat(call.error())
                .isInstanceOf(OpenAiCompatibleAgentGatewayException.class)
                .hasMessageContaining("missing terminal tool results");
        assertThat(transport.requests).isEmpty();
    }

    @Test
    void exposesRetryableHttpFailure() {
        QueueTransport transport = new QueueTransport(response(429, """
                { "error": { "message": "model is overloaded" } }
                """));

        AgentModelCall call = gateway(transport).submit(request(List.of(user()), List.of(), Map.of()));

        assertThat(call.poll()).isEqualTo(AgentModelCallStatus.FAILED);
        assertThat(call.error()).isInstanceOfSatisfying(
                OpenAiCompatibleAgentGatewayException.class,
                failure -> {
                    assertThat(failure.statusCode()).hasValue(429);
                    assertThat(failure.retryable()).isTrue();
                    assertThat(failure).hasMessageContaining("model is overloaded");
                });
    }

    @Test
    void redactsCredentialLikeTextFromProviderErrors() {
        QueueTransport transport = new QueueTransport(response(401, """
                { "error": { "message": "Incorrect key sk-proj-********SECRET_123" } }
                """));

        AgentModelCall call = gateway(transport).submit(request(List.of(user()), List.of(), Map.of()));

        assertThat(call.poll()).isEqualTo(AgentModelCallStatus.FAILED);
        assertThat(call.error())
                .hasMessageContaining("HTTP 401")
                .hasMessageContaining("sk-[REDACTED]")
                .hasMessageNotContaining("SECRET_123");
    }

    @Test
    void rejectsReservedExtraBodyFieldBeforeDispatch() {
        QueueTransport transport = new QueueTransport(response(200, finalResponse()));
        Map<String, Object> metadata = Map.of(
                OpenAiCompatibleAgentOptions.EXTRA_BODY,
                Map.of("messages", List.of()));

        AgentModelCall call = gateway(transport).submit(request(List.of(user()), List.of(), metadata));

        assertThat(call.poll()).isEqualTo(AgentModelCallStatus.FAILED);
        assertThat(call.error()).hasMessageContaining("reserved field: messages");
        assertThat(transport.requests).isEmpty();
    }

    @Test
    void cancellationCancelsPendingTransport() {
        CompletableFuture<HttpResponse<String>> pending = new CompletableFuture<>();
        OpenAiCompatibleHttpTransport transport = request -> pending;
        AgentModelCall call = gateway(transport).submit(request(List.of(user()), List.of(), Map.of()));

        assertThat(call.poll()).isEqualTo(AgentModelCallStatus.PENDING);

        call.cancel();
        call.cancel();

        assertThat(call.poll()).isEqualTo(AgentModelCallStatus.CANCELLED);
        assertThat(pending).isCancelled();
    }

    private OpenAiCompatibleAgentModelGateway gateway(OpenAiCompatibleHttpTransport transport) {
        OpenAiCompatibleAgentGatewayConfig config = OpenAiCompatibleAgentGatewayConfig
                .builder("http://127.0.0.1:8000/v1")
                .apiKey("test-key")
                .header("X-Tenant", "terminal-a")
                .build();
        return new OpenAiCompatibleAgentModelGateway(config, transport, objectMapper, TEST_CLOCK);
    }

    private AgentModelRequest request(
            List<AgentMessage> messages,
            List<ToolDefinition> tools,
            Map<String, Object> metadata
    ) {
        return new AgentModelRequest(
                "run-1",
                "thread-1",
                1,
                1,
                "qwen-local",
                messages,
                tools,
                Duration.ofSeconds(10),
                metadata);
    }

    private JsonNode requestJson(HttpRequest request) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CompletableFuture<byte[]> completed = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable failure) {
                completed.completeExceptionally(failure);
            }

            @Override
            public void onComplete() {
                completed.complete(output.toByteArray());
            }
        });
        return objectMapper.readTree(completed.join());
    }

    private static AgentMessage user() {
        return AgentMessage.user("Look up A-1.", TEST_INSTANT);
    }

    private static ToolDefinition toolDefinition() {
        return new ToolDefinition("lookup", "Look up an item", Map.of("type", "object"));
    }

    private static ResponseSpec response(int statusCode, String body) {
        return new ResponseSpec(statusCode, body);
    }

    private static String toolCallResponse() {
        return """
                {
                  "id": "chatcmpl-tool",
                  "model": "qwen-served",
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [{
                        "id": "call-search-1",
                        "type": "function",
                        "function": {
                          "name": "atcss.log.search",
                          "arguments": "{\\"equipmentId\\":\\"ARMG214\\"}"
                        }
                      }]
                    },
                    "finish_reason": "tool_calls"
                  }],
                  "usage": { "prompt_tokens": 9, "completion_tokens": 3 }
                }
                """;
    }

    private static String finalResponse() {
        return """
                {
                  "id": "chatcmpl-final",
                  "model": "qwen-served",
                  "system_fingerprint": "fp-test",
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": "ARMG214 has a high-severity incident."
                    },
                    "finish_reason": "stop"
                  }],
                  "usage": { "prompt_tokens": 12, "completion_tokens": 5 }
                }
                """;
    }

    private static final class QueueTransport implements OpenAiCompatibleHttpTransport {

        private final Deque<ResponseSpec> responses;
        private final List<HttpRequest> requests = new ArrayList<>();

        private QueueTransport(ResponseSpec... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public CompletableFuture<HttpResponse<String>> send(HttpRequest request) {
            requests.add(request);
            ResponseSpec response = responses.pollFirst();
            if (response == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("no response remains"));
            }
            return CompletableFuture.completedFuture(
                    new StubHttpResponse(response.statusCode(), request, response.body()));
        }
    }

    private record ResponseSpec(int statusCode, String body) {
    }

    private record StubHttpResponse(
            int statusCode,
            HttpRequest request,
            String body
    ) implements HttpResponse<String> {

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (name, value) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private static final class ImmediateTool implements AgentTool {

        private final ToolDefinition definition;
        private final String content;

        private ImmediateTool(ToolDefinition definition, String content) {
            this.definition = definition;
            this.content = content;
        }

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public AgentToolExecution start(ToolCall call, AgentToolContext context) {
            return new AgentToolExecution() {
                @Override
                public String executionId() {
                    return "execution-" + call.callId();
                }

                @Override
                public ToolExecutionStatus poll() {
                    return ToolExecutionStatus.READY;
                }

                @Override
                public ToolResult result() {
                    return ToolResult.succeeded(call.callId(), call.toolName(), content);
                }

                @Override
                public Throwable error() {
                    return null;
                }

                @Override
                public void cancel() {
                    // Already complete.
                }
            };
        }
    }
}
