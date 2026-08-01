package io.github.flowerjvm.flower.agent.flow;

import io.github.flowerjvm.flower.agent.AgentSpec;
import io.github.flowerjvm.flower.agent.control.AgentBudget;
import io.github.flowerjvm.flower.agent.control.CompletionPolicy;
import io.github.flowerjvm.flower.agent.control.ModelTurnRetryPolicy;
import io.github.flowerjvm.flower.agent.gateway.AgentModelCall;
import io.github.flowerjvm.flower.agent.gateway.AgentModelCallStatus;
import io.github.flowerjvm.flower.agent.gateway.AgentModelGateway;
import io.github.flowerjvm.flower.agent.model.AgentMessage;
import io.github.flowerjvm.flower.agent.model.AgentModelRequest;
import io.github.flowerjvm.flower.agent.model.AgentModelResponse;
import io.github.flowerjvm.flower.agent.model.AgentRole;
import io.github.flowerjvm.flower.agent.model.AgentUsage;
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
import io.github.flowerjvm.flower.agent.tool.ToolRegistry;
import io.github.flowerjvm.flower.agent.transcript.ContextBuilder;
import io.github.flowerjvm.flower.agent.transcript.InMemoryTranscriptStore;
import io.github.flowerjvm.flower.testkit.FlowTestHarness;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunFlowFactoryTest {

    private static final Instant TEST_INSTANT = Instant.EPOCH;
    private static final Clock TEST_CLOCK = Clock.fixed(TEST_INSTANT, ZoneOffset.UTC);
    private static final ToolRegistry NO_TOOLS = new InMemoryToolRegistry(List.of());

    @Test
    void registeredToolDeterminesCapabilityAndTranscriptPreservesProtocol() {
        ToolCall lookupCall = new ToolCall("tool-1", "customer.lookup", Map.of("customerId", "C-7"));
        SequenceGateway gateway = new SequenceGateway(
                readyModel(response("", List.of(lookupCall), new AgentUsage(20, 4))),
                readyModel(response("Customer C-7 is active.", List.of(), new AgentUsage(30, 8))));
        RecordingTool tool = tool(
                "customer.lookup",
                readyTool(ToolResult.succeeded("tool-1", "customer.lookup", "{\"active\":true}")));
        InMemoryTranscriptStore transcripts = new InMemoryTranscriptStore();
        AgentRunFlow runFlow = factory(gateway, registry(tool), transcripts)
                .createFlow(
                        AgentSpec.of("support-agent", "local:model", "You are a support agent."),
                        thread("thread-1"),
                        user("Look up customer C-7."));

        try (FlowTestHarness harness = FlowTestHarness.create()) {
            harness.submit(runFlow.flow()).ticks(24);
            harness.assertFlow(AgentRunFlowFactory.FLOW_TYPE, runFlow.run().runId()).isFinished();
        }

        assertThat(runFlow.run().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(runFlow.run().turnCount()).isEqualTo(2);
        assertThat(runFlow.run().toolCalls()).isEqualTo(1);
        assertThat(runFlow.run().inputTokens()).isEqualTo(50);
        assertThat(runFlow.run().outputTokens()).isEqualTo(12);
        assertThat(runFlow.run().finalMessage().content()).isEqualTo("Customer C-7 is active.");
        assertThat(gateway.requests).hasSize(2);
        assertThat(gateway.requests.get(1).messages())
                .extracting(AgentMessage::role)
                .containsExactly(
                        AgentRole.SYSTEM,
                        AgentRole.USER,
                        AgentRole.ASSISTANT,
                        AgentRole.TOOL);
        AgentMessage assistantToolRequest = gateway.requests.get(1).messages().get(2);
        assertThat(assistantToolRequest.toolCalls()).containsExactly(lookupCall);
        assertThat(tool.calls).extracting(ToolCall::callId).containsExactly("tool-1");
        assertThat(runFlow.transcript())
                .extracting(AgentMessage::role)
                .containsExactly(
                        AgentRole.USER,
                        AgentRole.ASSISTANT,
                        AgentRole.TOOL,
                        AgentRole.ASSISTANT);
        assertThat(runFlow.transcript()).extracting(AgentMessage::createdAt)
                .containsOnly(TEST_INSTANT);
    }

    @Test
    void modelTurnRetryWaitsForBackoffAndCancelsFailedHandle() {
        TrackingModelCall failedCall = failedModel(new IllegalStateException("temporary provider failure"));
        SequenceGateway gateway = new SequenceGateway(
                failedCall,
                readyModel(response("Recovered.", List.of(), new AgentUsage(10, 2))));
        AgentSpec spec = spec(
                "retry-agent",
                AgentBudget.defaults(),
                Duration.ofSeconds(10),
                Duration.ofSeconds(10),
                CompletionPolicy.toolCallsThenText(),
                ModelTurnRetryPolicy.maxAttempts(2, Duration.ofSeconds(2)));
        AgentRunFlow runFlow = factory(gateway, NO_TOOLS, new InMemoryTranscriptStore())
                .createFlow(spec, thread("thread-retry"), user("Try once."));

        try (FlowTestHarness harness = FlowTestHarness.create()) {
            harness.submit(runFlow.flow()).ticks(4);

            assertThat(gateway.requests).hasSize(1);
            assertThat(failedCall.cancelCount).isEqualTo(1);

            harness.advanceAndTick(1_999);
            assertThat(gateway.requests).hasSize(1);

            harness.advanceAndTick(1).ticks(3);
        }

        assertThat(runFlow.run().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(runFlow.run().turnCount()).isEqualTo(1);
        assertThat(runFlow.run().turns().get(0).attempts()).isEqualTo(2);
        assertThat(gateway.requests).extracting(AgentModelRequest::attempt).containsExactly(1, 2);
    }

    @Test
    void turnBudgetStopsLoopBeforeASecondModelTurn() {
        ToolCall call = new ToolCall("tool-budget", "customer.lookup", Map.of());
        SequenceGateway gateway = new SequenceGateway(
                readyModel(response("", List.of(call), AgentUsage.NONE)));
        RecordingTool tool = tool(
                "customer.lookup",
                readyTool(ToolResult.succeeded(call.callId(), call.toolName(), "{}")));
        AgentBudget oneTurn = new AgentBudget(1, 10, 1_000, 1_000, Duration.ofMinutes(5));
        AgentRunFlow runFlow = factory(gateway, registry(tool), new InMemoryTranscriptStore())
                .createFlow(
                        spec("bounded-agent", oneTurn),
                        thread("thread-budget"),
                        user("Keep going."));

        try (FlowTestHarness harness = FlowTestHarness.create()) {
            harness.submit(runFlow.flow()).ticks(20);
        }

        assertThat(runFlow.run().status()).isEqualTo(AgentRunStatus.BUDGET_EXHAUSTED);
        assertThat(runFlow.run().budgetViolation()).hasToString("TURN_LIMIT");
        assertThat(runFlow.run().turnCount()).isEqualTo(1);
        assertThat(gateway.requests).hasSize(1);
        assertToolProtocolClosed(runFlow.transcript(), List.of(call));
    }

    @Test
    void interruptedToolClosesRemainingBatchAndPreservesResumeToken() {
        ToolCall approvalCall = new ToolCall("approval-call", "refund.request", Map.of("orderId", "O-9"));
        ToolCall remainingCall = new ToolCall("notify-call", "customer.notify", Map.of());
        SequenceGateway gateway = new SequenceGateway(
                readyModel(response("", List.of(approvalCall, remainingCall), AgentUsage.NONE)));
        RecordingTool approvalTool = tool(
                "refund.request",
                readyTool(ToolResult.interrupted(
                        approvalCall.callId(),
                        approvalCall.toolName(),
                        "Waiting for refund approval.",
                        "approval:run-44",
                        Map.of("approvalId", "approval-44"))));
        RecordingTool notificationTool = tool(
                "customer.notify",
                readyTool(ToolResult.succeeded(remainingCall.callId(), remainingCall.toolName(), "sent")));
        AgentRunFlow runFlow = factory(
                gateway,
                registry(approvalTool, notificationTool),
                new InMemoryTranscriptStore())
                .createFlow(
                        AgentSpec.of("refund-agent", "local:model", ""),
                        thread("thread-approval"),
                        user("Refund order O-9."));

        try (FlowTestHarness harness = FlowTestHarness.create()) {
            harness.submit(runFlow.flow()).ticks(16);
        }

        assertThat(runFlow.run().status()).isEqualTo(AgentRunStatus.INTERRUPTED);
        assertThat(runFlow.run().interrupt().resumeToken()).isEqualTo("approval:run-44");
        assertThat(runFlow.run().interrupt().metadata()).containsEntry("approvalId", "approval-44");
        assertThat(notificationTool.calls).isEmpty();
        assertToolProtocolClosed(runFlow.transcript(), List.of(approvalCall, remainingCall));
        assertThat(toolMessages(runFlow.transcript()))
                .extracting(message -> message.metadata().get("status"))
                .containsExactly("INTERRUPTED", "CANCELLED");
    }

    @Test
    void toolCallBudgetClosesUnexecutedCalls() {
        ToolCall first = new ToolCall("call-1", "lookup", Map.of());
        ToolCall second = new ToolCall("call-2", "lookup", Map.of());
        SequenceGateway gateway = new SequenceGateway(
                readyModel(response("", List.of(first, second), AgentUsage.NONE)));
        RecordingTool tool = tool(
                "lookup",
                readyTool(ToolResult.succeeded(first.callId(), first.toolName(), "ok")));
        AgentBudget oneTool = new AgentBudget(4, 1, 1_000, 1_000, Duration.ofMinutes(5));
        AgentRunFlow runFlow = factory(gateway, registry(tool), new InMemoryTranscriptStore())
                .createFlow(spec("tool-budget-agent", oneTool), thread("thread-tool-budget"), user("Lookup twice."));

        try (FlowTestHarness harness = FlowTestHarness.create()) {
            harness.submit(runFlow.flow()).ticks(16);
        }

        assertThat(runFlow.run().status()).isEqualTo(AgentRunStatus.BUDGET_EXHAUSTED);
        assertThat(runFlow.run().budgetViolation()).hasToString("TOOL_CALL_LIMIT");
        assertThat(tool.calls).containsExactly(first);
        assertToolProtocolClosed(runFlow.transcript(), List.of(first, second));
        assertThat(toolMessages(runFlow.transcript()))
                .extracting(message -> message.metadata().get("status"))
                .containsExactly("SUCCEEDED", "CANCELLED");
    }

    @Test
    void modelTimeoutCancelsPendingHandle() {
        TrackingModelCall pendingCall = pendingModel();
        SequenceGateway gateway = new SequenceGateway(pendingCall);
        AgentSpec spec = spec(
                "model-timeout-agent",
                AgentBudget.defaults(),
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                CompletionPolicy.toolCallsThenText(),
                ModelTurnRetryPolicy.noRetry());
        AgentRunFlow runFlow = factory(gateway, NO_TOOLS, new InMemoryTranscriptStore())
                .createFlow(spec, thread("thread-model-timeout"), user("Wait for model."));

        try (FlowTestHarness harness = FlowTestHarness.create()) {
            harness.submit(runFlow.flow()).ticks(3).advanceAndTick(1_000).tick();
        }

        assertThat(runFlow.run().status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(runFlow.run().failureCode()).isEqualTo("MODEL_TURN_FAILED");
        assertThat(pendingCall.cancelCount).isEqualTo(1);
    }

    @Test
    void toolTimeoutCancelsHandleAndReturnsFailureToNextTurn() {
        ToolCall call = new ToolCall("slow-call", "slow.lookup", Map.of());
        SequenceGateway gateway = new SequenceGateway(
                readyModel(response("", List.of(call), AgentUsage.NONE)),
                readyModel(response("The lookup timed out.", List.of(), AgentUsage.NONE)));
        TrackingToolExecution pendingExecution = pendingTool();
        RecordingTool tool = tool("slow.lookup", pendingExecution);
        AgentSpec spec = spec(
                "tool-timeout-agent",
                AgentBudget.defaults(),
                Duration.ofSeconds(10),
                Duration.ofSeconds(1),
                CompletionPolicy.toolCallsThenText(),
                ModelTurnRetryPolicy.noRetry());
        AgentRunFlow runFlow = factory(gateway, registry(tool), new InMemoryTranscriptStore())
                .createFlow(spec, thread("thread-tool-timeout"), user("Use the slow tool."));

        try (FlowTestHarness harness = FlowTestHarness.create()) {
            harness.submit(runFlow.flow()).ticks(6).advanceAndTick(1_000).ticks(8);
        }

        assertThat(runFlow.run().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(pendingExecution.cancelCount).isEqualTo(1);
        AgentMessage toolMessage = toolMessages(runFlow.transcript()).get(0);
        assertThat(toolMessage.metadata())
                .containsEntry("status", "FAILED")
                .containsEntry("errorCode", "TOOL_EXECUTION_FAILED");
    }

    @Test
    void externalCancelCancelsPendingModelHandle() {
        TrackingModelCall pendingCall = pendingModel();
        AgentRunFlow runFlow = factory(
                new SequenceGateway(pendingCall),
                NO_TOOLS,
                new InMemoryTranscriptStore())
                .createFlow(
                        AgentSpec.of("cancel-agent", "local:model", ""),
                        thread("thread-cancel"),
                        user("Start and cancel."));

        try (FlowTestHarness harness = FlowTestHarness.create()) {
            harness.submit(runFlow.flow()).ticks(3);
            runFlow.cancel("operator cancelled");
        }

        assertThat(runFlow.run().status()).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(pendingCall.cancelCount).isEqualTo(1);
    }

    @Test
    void unknownToolIsReturnedAsStructuredFailure() {
        ToolCall unknown = new ToolCall("unknown-call", "missing.tool", Map.of());
        SequenceGateway gateway = new SequenceGateway(
                readyModel(response("", List.of(unknown), AgentUsage.NONE)),
                readyModel(response("That tool is unavailable.", List.of(), AgentUsage.NONE)));
        AgentRunFlow runFlow = factory(gateway, NO_TOOLS, new InMemoryTranscriptStore())
                .createFlow(
                        AgentSpec.of("unknown-tool-agent", "local:model", ""),
                        thread("thread-unknown"),
                        user("Use a missing tool."));

        try (FlowTestHarness harness = FlowTestHarness.create()) {
            harness.submit(runFlow.flow()).ticks(20);
        }

        assertThat(runFlow.run().status()).isEqualTo(AgentRunStatus.COMPLETED);
        AgentMessage toolMessage = toolMessages(runFlow.transcript()).get(0);
        assertThat(toolMessage.metadata())
                .containsEntry("status", "FAILED")
                .containsEntry("errorCode", "UNKNOWN_TOOL");
        assertToolProtocolClosed(runFlow.transcript(), List.of(unknown));
    }

    @Test
    void usageBudgetClosesToolCallsBeforeExecution() {
        ToolCall call = new ToolCall("expensive-call", "expensive.tool", Map.of());
        SequenceGateway gateway = new SequenceGateway(
                readyModel(response("", List.of(call), new AgentUsage(11, 1))));
        RecordingTool tool = tool(
                "expensive.tool",
                readyTool(ToolResult.succeeded(call.callId(), call.toolName(), "should not run")));
        AgentBudget budget = new AgentBudget(4, 4, 10, 100, Duration.ofMinutes(5));
        AgentRunFlow runFlow = factory(gateway, registry(tool), new InMemoryTranscriptStore())
                .createFlow(spec("usage-budget-agent", budget), thread("thread-usage"), user("Spend tokens."));

        try (FlowTestHarness harness = FlowTestHarness.create()) {
            harness.submit(runFlow.flow()).ticks(12);
        }

        assertThat(runFlow.run().status()).isEqualTo(AgentRunStatus.BUDGET_EXHAUSTED);
        assertThat(runFlow.run().budgetViolation()).hasToString("INPUT_TOKEN_LIMIT");
        assertThat(tool.calls).isEmpty();
        assertToolProtocolClosed(runFlow.transcript(), List.of(call));
        assertThat(toolMessages(runFlow.transcript()).get(0).metadata())
                .containsEntry("status", "CANCELLED")
                .containsEntry("errorCode", "BUDGET_INPUT_TOKEN_LIMIT");
    }

    @Test
    void completionPolicyFailureRecordsAssistantOnce() {
        SequenceGateway gateway = new SequenceGateway(
                readyModel(response("Candidate answer.", List.of(), AgentUsage.NONE)));
        CompletionPolicy throwingPolicy = (run, response) -> {
            throw new IllegalStateException("broken completion policy");
        };
        AgentSpec spec = spec(
                "policy-agent",
                AgentBudget.defaults(),
                Duration.ofSeconds(10),
                Duration.ofSeconds(10),
                throwingPolicy,
                ModelTurnRetryPolicy.noRetry());
        AgentRunFlow runFlow = factory(gateway, NO_TOOLS, new InMemoryTranscriptStore())
                .createFlow(spec, thread("thread-policy"), user("Answer."));

        try (FlowTestHarness harness = FlowTestHarness.create()) {
            harness.submit(runFlow.flow()).ticks(10);
        }

        assertThat(runFlow.run().status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(runFlow.run().failureCode()).isEqualTo("COMPLETION_POLICY_FAILED");
        assertThat(runFlow.transcript())
                .filteredOn(message -> message.role() == AgentRole.ASSISTANT)
                .hasSize(1);
    }

    @Test
    void createFlowDoesNotMutateTranscriptBeforeSubmission() {
        InMemoryTranscriptStore transcripts = new InMemoryTranscriptStore();
        AgentThread thread = thread("thread-pure-factory");
        AgentRunFlow runFlow = factory(
                new SequenceGateway(readyModel(response("Done.", List.of(), AgentUsage.NONE))),
                NO_TOOLS,
                transcripts)
                .createFlow(AgentSpec.of("pure-agent", "local:model", ""), thread, user("Hello."));

        assertThat(transcripts.messages(thread.threadId())).isEmpty();

        try (FlowTestHarness harness = FlowTestHarness.create()) {
            harness.submit(runFlow.flow()).tick();
        }

        assertThat(transcripts.messages(thread.threadId())).extracting(AgentMessage::role)
                .containsExactly(AgentRole.USER);
    }

    private static AgentRunFlowFactory factory(
            AgentModelGateway gateway,
            ToolRegistry registry,
            InMemoryTranscriptStore transcripts
    ) {
        return new AgentRunFlowFactory(gateway, registry, transcripts, TEST_CLOCK);
    }

    private static AgentSpec spec(String agentId, AgentBudget budget) {
        return spec(
                agentId,
                budget,
                Duration.ofSeconds(10),
                Duration.ofSeconds(10),
                CompletionPolicy.toolCallsThenText(),
                ModelTurnRetryPolicy.noRetry());
    }

    private static AgentSpec spec(
            String agentId,
            AgentBudget budget,
            Duration modelTimeout,
            Duration toolTimeout,
            CompletionPolicy completionPolicy,
            ModelTurnRetryPolicy retryPolicy
    ) {
        return new AgentSpec(
                agentId,
                "local:model",
                "",
                budget,
                modelTimeout,
                toolTimeout,
                ContextBuilder.fullTranscript(),
                completionPolicy,
                retryPolicy,
                Map.of());
    }

    private static AgentThread thread(String threadId) {
        return new AgentThread(threadId, TEST_INSTANT, Map.of());
    }

    private static AgentMessage user(String content) {
        return AgentMessage.user(content, TEST_INSTANT);
    }

    private static AgentModelResponse response(
            String content,
            List<ToolCall> toolCalls,
            AgentUsage usage
    ) {
        return new AgentModelResponse(
                AgentMessage.assistant(content, TEST_INSTANT),
                toolCalls,
                usage,
                toolCalls.isEmpty() ? "stop" : "tool_calls",
                Map.of());
    }

    private static TrackingModelCall readyModel(AgentModelResponse response) {
        return new TrackingModelCall(AgentModelCallStatus.READY, response, null);
    }

    private static TrackingModelCall failedModel(Throwable failure) {
        return new TrackingModelCall(AgentModelCallStatus.FAILED, null, failure);
    }

    private static TrackingModelCall pendingModel() {
        return new TrackingModelCall(AgentModelCallStatus.PENDING, null, null);
    }

    private static TrackingToolExecution readyTool(ToolResult result) {
        return new TrackingToolExecution(ToolExecutionStatus.READY, result, null);
    }

    private static TrackingToolExecution pendingTool() {
        return new TrackingToolExecution(ToolExecutionStatus.PENDING, null, null);
    }

    private static RecordingTool tool(String name, AgentToolExecution... executions) {
        return new RecordingTool(name, executions);
    }

    private static ToolRegistry registry(AgentTool... tools) {
        return new InMemoryToolRegistry(List.of(tools));
    }

    private static List<AgentMessage> toolMessages(List<AgentMessage> transcript) {
        return transcript.stream().filter(message -> message.role() == AgentRole.TOOL).toList();
    }

    private static void assertToolProtocolClosed(List<AgentMessage> transcript, List<ToolCall> expectedCalls) {
        List<ToolCall> declared = transcript.stream()
                .filter(message -> message.role() == AgentRole.ASSISTANT)
                .flatMap(message -> message.toolCalls().stream())
                .toList();
        List<String> resultCallIds = toolMessages(transcript).stream()
                .map(AgentMessage::toolCallId)
                .toList();
        assertThat(declared).containsExactlyElementsOf(expectedCalls);
        assertThat(resultCallIds).containsExactlyElementsOf(expectedCalls.stream().map(ToolCall::callId).toList());
    }

    private static final class SequenceGateway implements AgentModelGateway {
        private final Deque<AgentModelCall> sequence;
        private final List<AgentModelRequest> requests = new ArrayList<>();

        private SequenceGateway(AgentModelCall... sequence) {
            this.sequence = new ArrayDeque<>(List.of(sequence));
        }

        @Override
        public AgentModelCall submit(AgentModelRequest request) {
            requests.add(request);
            AgentModelCall next = sequence.pollFirst();
            if (next == null) {
                throw new IllegalStateException("no fake model call remains");
            }
            return next;
        }
    }

    private static final class TrackingModelCall implements AgentModelCall {
        private final AgentModelCallStatus status;
        private final AgentModelResponse response;
        private final Throwable failure;
        private int cancelCount;

        private TrackingModelCall(
                AgentModelCallStatus status,
                AgentModelResponse response,
                Throwable failure
        ) {
            this.status = status;
            this.response = response;
            this.failure = failure;
        }

        @Override
        public String callId() {
            return "fake-model-call";
        }

        @Override
        public AgentModelCallStatus poll() {
            return status;
        }

        @Override
        public AgentModelResponse result() {
            return response;
        }

        @Override
        public Throwable error() {
            return failure;
        }

        @Override
        public void cancel() {
            cancelCount++;
        }
    }

    private static final class RecordingTool implements AgentTool {
        private final ToolDefinition definition;
        private final Deque<AgentToolExecution> executions;
        private final List<ToolCall> calls = new ArrayList<>();

        private RecordingTool(String name, AgentToolExecution... executions) {
            this.definition = new ToolDefinition(name, "Test tool " + name, Map.of("type", "object"));
            this.executions = new ArrayDeque<>(List.of(executions));
        }

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public AgentToolExecution start(ToolCall call, AgentToolContext context) {
            calls.add(call);
            AgentToolExecution next = executions.pollFirst();
            if (next == null) {
                throw new IllegalStateException("no fake tool execution remains");
            }
            return next;
        }
    }

    private static final class TrackingToolExecution implements AgentToolExecution {
        private final ToolExecutionStatus status;
        private final ToolResult result;
        private final Throwable failure;
        private int cancelCount;

        private TrackingToolExecution(
                ToolExecutionStatus status,
                ToolResult result,
                Throwable failure
        ) {
            this.status = status;
            this.result = result;
            this.failure = failure;
        }

        @Override
        public String executionId() {
            return "fake-tool-execution";
        }

        @Override
        public ToolExecutionStatus poll() {
            return status;
        }

        @Override
        public ToolResult result() {
            return result;
        }

        @Override
        public Throwable error() {
            return failure;
        }

        @Override
        public void cancel() {
            cancelCount++;
        }
    }
}

