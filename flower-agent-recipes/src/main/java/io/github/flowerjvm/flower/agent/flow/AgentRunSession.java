package io.github.flowerjvm.flower.agent.flow;

import io.github.flowerjvm.flower.agent.AgentSpec;
import io.github.flowerjvm.flower.agent.control.BudgetViolation;
import io.github.flowerjvm.flower.agent.model.AgentMessage;
import io.github.flowerjvm.flower.agent.model.AgentModelRequest;
import io.github.flowerjvm.flower.agent.model.AgentModelResponse;
import io.github.flowerjvm.flower.agent.model.AgentUsage;
import io.github.flowerjvm.flower.agent.model.ToolCall;
import io.github.flowerjvm.flower.agent.model.ToolResult;
import io.github.flowerjvm.flower.agent.observation.AgentEvent;
import io.github.flowerjvm.flower.agent.observation.AgentEventSink;
import io.github.flowerjvm.flower.agent.observation.AgentEventType;
import io.github.flowerjvm.flower.agent.run.AgentInterrupt;
import io.github.flowerjvm.flower.agent.run.AgentRun;
import io.github.flowerjvm.flower.agent.run.AgentRunStatus;
import io.github.flowerjvm.flower.agent.run.AgentThread;
import io.github.flowerjvm.flower.agent.run.AgentTurn;
import io.github.flowerjvm.flower.agent.run.AgentTurnStatus;
import io.github.flowerjvm.flower.agent.tool.AgentToolContext;
import io.github.flowerjvm.flower.agent.tool.ToolRegistry;
import io.github.flowerjvm.flower.agent.transcript.TranscriptStore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Mutable state owned by one transient Flower Flow.
 */
final class AgentRunSession {

    private final String runId = UUID.randomUUID().toString();
    private final AgentSpec spec;
    private final AgentThread thread;
    private final TranscriptStore transcriptStore;
    private final ToolRegistry toolRegistry;
    private final AgentMessage initialMessage;
    private final Instant startedAt;
    private final String recipeId;
    private final AgentEventSink eventSink;
    private final List<AgentTurn> turns = new ArrayList<>();

    private boolean transcriptInitialized;
    private AgentRunStatus status = AgentRunStatus.CREATED;
    private Instant updatedAt;
    private Instant endedAt;
    private int toolCalls;
    private long inputTokens;
    private long outputTokens;
    private AgentMessage finalMessage;
    private AgentInterrupt interrupt;
    private BudgetViolation budgetViolation;
    private String failureCode;
    private String failureMessage;
    private long eventSequence;

    private AgentModelRequest currentRequest;
    private AgentModelResponse currentResponse;
    private List<ToolCall> pendingToolCalls = List.of();
    private int pendingToolIndex;

    AgentRunSession(
            AgentSpec spec,
            AgentThread thread,
            TranscriptStore transcriptStore,
            ToolRegistry toolRegistry,
            AgentMessage initialMessage,
            Instant startedAt,
            String recipeId,
            AgentEventSink eventSink
    ) {
        this.spec = spec;
        this.thread = thread;
        this.transcriptStore = transcriptStore;
        this.toolRegistry = toolRegistry;
        this.initialMessage = initialMessage;
        this.startedAt = startedAt;
        this.updatedAt = startedAt;
        this.recipeId = recipeId;
        this.eventSink = eventSink == null ? AgentEventSink.noop() : eventSink;
    }

    synchronized AgentRun snapshot() {
        return new AgentRun(
                runId,
                spec.agentId(),
                thread.threadId(),
                status,
                startedAt,
                updatedAt,
                endedAt,
                turns,
                toolCalls,
                inputTokens,
                outputTokens,
                finalMessage,
                interrupt,
                budgetViolation,
                failureCode,
                failureMessage);
    }

    synchronized AgentMessage takeInitialMessage(Instant now) {
        if (transcriptInitialized) {
            return null;
        }
        transcriptInitialized = true;
        touch(now);
        emit(AgentEventType.RUN_STARTED, now, 0, null, null, Map.of());
        return initialMessage;
    }

    synchronized boolean beginNextTurn(Instant now) {
        if (status.isTerminal()) {
            return false;
        }
        Optional<BudgetViolation> violation = spec.budget().beforeTurn(
                turns.size(),
                inputTokens,
                outputTokens,
                startedAt,
                now);
        if (violation.isPresent()) {
            exhaust(violation.get(), now);
            return false;
        }

        int turnNumber = turns.size() + 1;
        AgentTurn turn = new AgentTurn(
                turnNumber,
                AgentTurnStatus.IN_PROGRESS,
                1,
                now,
                null,
                AgentUsage.NONE,
                null);
        turns.add(turn);
        status = AgentRunStatus.RUNNING;
        currentRequest = null;
        currentResponse = null;
        touch(now);
        emit(AgentEventType.TURN_STARTED, now, turnNumber, null, spec.modelId(), Map.of(
                "attempt", 1));
        return true;
    }

    synchronized void prepareCurrentRequest(List<AgentMessage> selectedMessages, Instant now) {
        if (selectedMessages == null) {
            throw new IllegalArgumentException("contextBuilder returned null messages");
        }
        AgentTurn turn = currentTurn();
        List<AgentMessage> modelMessages = new ArrayList<>(selectedMessages.size() + 1);
        if (!spec.systemPrompt().isBlank()) {
            modelMessages.add(AgentMessage.system(spec.systemPrompt(), now));
        }
        modelMessages.addAll(selectedMessages);
        currentRequest = new AgentModelRequest(
                runId,
                thread.threadId(),
                turn.turnNumber(),
                1,
                spec.modelId(),
                modelMessages,
                toolRegistry.definitions(),
                spec.modelTimeout(),
                spec.metadata());
        touch(now);
    }

    synchronized AgentModelRequest currentRequest() {
        if (currentRequest == null) {
            throw new IllegalStateException("no model request is prepared");
        }
        return currentRequest;
    }

    synchronized int currentAttempt() {
        return currentTurn().attempts();
    }

    synchronized void markWaitingModel(Instant now) {
        status = AgentRunStatus.WAITING_MODEL;
        touch(now);
    }

    synchronized void prepareModelRetry(Instant now) {
        AgentTurn turn = currentTurn();
        int nextAttempt = turn.attempts() + 1;
        replaceCurrentTurn(new AgentTurn(
                turn.turnNumber(),
                AgentTurnStatus.IN_PROGRESS,
                nextAttempt,
                turn.startedAt(),
                null,
                AgentUsage.NONE,
                null));
        currentRequest = new AgentModelRequest(
                currentRequest.runId(),
                currentRequest.threadId(),
                currentRequest.turnNumber(),
                nextAttempt,
                currentRequest.modelId(),
                currentRequest.messages(),
                currentRequest.tools(),
                currentRequest.timeout(),
                currentRequest.metadata());
        status = AgentRunStatus.RUNNING;
        touch(now);
    }

    synchronized void acceptModelResponse(String callId, AgentModelResponse response, Instant now) {
        if (response == null) {
            throw new IllegalArgumentException("response must not be null");
        }
        AgentTurn turn = currentTurn();
        AgentUsage usage = response.usage();
        replaceCurrentTurn(new AgentTurn(
                turn.turnNumber(),
                AgentTurnStatus.SUCCEEDED,
                turn.attempts(),
                turn.startedAt(),
                now,
                usage,
                null));
        inputTokens += usage.inputTokens();
        outputTokens += usage.outputTokens();
        currentResponse = response.withAssistantMessage(response.assistantMessage().withCreatedAt(now));
        pendingToolCalls = currentResponse.toolCalls();
        pendingToolIndex = 0;
        status = AgentRunStatus.RUNNING;
        touch(now);
        emit(AgentEventType.MODEL_CALL_COMPLETED, now, turn.turnNumber(), callId, spec.modelId(), attributes(
                "attempt", turn.attempts(),
                "inputTokens", usage.inputTokens(),
                "outputTokens", usage.outputTokens(),
                "finishReason", response.finishReason()));
        emit(AgentEventType.TURN_COMPLETED, now, turn.turnNumber(), null, spec.modelId(), Map.of(
                "status", AgentTurnStatus.SUCCEEDED.name(),
                "attempts", turn.attempts()));
    }

    synchronized AgentModelResponse currentResponse() {
        if (currentResponse == null) {
            throw new IllegalStateException("no model response is available");
        }
        return currentResponse;
    }

    synchronized boolean applyUsageBudget(Instant now) {
        Optional<BudgetViolation> violation = spec.budget().afterUsage(
                inputTokens,
                outputTokens,
                startedAt,
                now);
        if (violation.isPresent()) {
            exhaust(violation.get(), now);
            return false;
        }
        return true;
    }

    synchronized void failCurrentTurn(String code, Throwable failure, Instant now) {
        AgentTurn turn = currentTurn();
        String message = failureMessage(failure);
        replaceCurrentTurn(new AgentTurn(
                turn.turnNumber(),
                AgentTurnStatus.FAILED,
                turn.attempts(),
                turn.startedAt(),
                now,
                AgentUsage.NONE,
                message));
        emit(AgentEventType.TURN_COMPLETED, now, turn.turnNumber(), null, spec.modelId(), attributes(
                "status", AgentTurnStatus.FAILED.name(),
                "attempts", turn.attempts(),
                "failureCode", code,
                "failureMessage", message));
        fail(code, message, now);
    }

    synchronized void modelCallSubmitted(String callId, Instant now) {
        AgentTurn turn = currentTurn();
        emit(AgentEventType.MODEL_CALL_SUBMITTED, now, turn.turnNumber(), callId, spec.modelId(), Map.of(
                "attempt", turn.attempts()));
    }

    synchronized void modelCallFailed(String callId, Throwable failure, Instant now) {
        AgentTurn turn = currentTurn();
        emit(AgentEventType.MODEL_CALL_FAILED, now, turn.turnNumber(), callId, spec.modelId(), attributes(
                "attempt", turn.attempts(),
                "failureMessage", failureMessage(failure)));
    }

    synchronized void modelRetryScheduled(Duration delay, Instant now) {
        AgentTurn turn = currentTurn();
        emit(AgentEventType.MODEL_RETRY_SCHEDULED, now, turn.turnNumber(), null, spec.modelId(), Map.of(
                "attempt", turn.attempts(),
                "delayMillis", delay.toMillis()));
    }

    synchronized void beginToolBatch(Instant now) {
        if (pendingToolCalls.isEmpty()) {
            throw new IllegalStateException("no pending tool calls");
        }
        status = AgentRunStatus.RUNNING;
        touch(now);
    }

    synchronized ToolCall currentToolCall() {
        return pendingToolIndex < pendingToolCalls.size()
                ? pendingToolCalls.get(pendingToolIndex)
                : null;
    }

    synchronized boolean reserveToolCall(Instant now) {
        Optional<BudgetViolation> violation = spec.budget().beforeTool(
                toolCalls,
                inputTokens,
                outputTokens,
                startedAt,
                now);
        if (violation.isPresent()) {
            exhaust(violation.get(), now);
            return false;
        }
        toolCalls++;
        status = AgentRunStatus.WAITING_TOOL;
        touch(now);
        ToolCall call = currentToolCall();
        if (call != null) {
            emit(AgentEventType.TOOL_CALL_STARTED, now, currentTurn().turnNumber(), call.callId(), call.toolName(),
                    Map.of("toolCallNumber", toolCalls));
        }
        return true;
    }

    synchronized AgentToolContext toolContext() {
        return new AgentToolContext(
                runId,
                thread.threadId(),
                currentTurn().turnNumber(),
                mergedToolMetadata());
    }

    synchronized void recordToolResult(ToolResult result, String executionId, Instant now) {
        transcriptStore.append(thread.threadId(), AgentMessage.tool(result, now));
        emitToolResult(result, executionId, now);
        pendingToolIndex++;
        if (!status.isTerminal()) {
            status = AgentRunStatus.RUNNING;
        }
        touch(now);
    }

    synchronized void finishToolBatch(Instant now) {
        if (pendingToolIndex < pendingToolCalls.size()) {
            throw new IllegalStateException("cannot finish a tool batch with pending calls");
        }
        pendingToolCalls = List.of();
        pendingToolIndex = 0;
        currentRequest = null;
        currentResponse = null;
        status = AgentRunStatus.RUNNING;
        touch(now);
    }

    synchronized void complete(AgentMessage message, Instant now) {
        finalMessage = message;
        status = AgentRunStatus.COMPLETED;
        terminal(now, "RUN_COMPLETED", "tool call was not executed because the agent run completed");
    }

    synchronized void interrupt(String reason, String resumeToken, Map<String, Object> metadata, Instant now) {
        interrupt = new AgentInterrupt(reason, resumeToken, metadata);
        status = AgentRunStatus.INTERRUPTED;
        terminal(now, "RUN_INTERRUPTED", "tool call was not executed because the agent run was interrupted");
    }

    synchronized void fail(String code, String message, Instant now) {
        failureCode = code == null ? "AGENT_FAILED" : code;
        failureMessage = message == null ? "" : message;
        status = AgentRunStatus.FAILED;
        terminal(now, "RUN_FAILED", "tool call was not executed because the agent run failed");
    }

    synchronized void cancel(String reason, Instant now) {
        if (status.isTerminal()) {
            return;
        }
        if (!turns.isEmpty()) {
            AgentTurn turn = currentTurn();
            if (turn.status() == AgentTurnStatus.IN_PROGRESS) {
                replaceCurrentTurn(new AgentTurn(
                        turn.turnNumber(),
                        AgentTurnStatus.CANCELLED,
                        turn.attempts(),
                        turn.startedAt(),
                        now,
                        AgentUsage.NONE,
                        reason));
                emit(AgentEventType.TURN_COMPLETED, now, turn.turnNumber(), null, spec.modelId(), attributes(
                        "status", AgentTurnStatus.CANCELLED.name(),
                        "attempts", turn.attempts(),
                        "failureMessage", reason));
            }
        }
        failureCode = "CANCELLED";
        failureMessage = reason == null ? "" : reason;
        status = AgentRunStatus.CANCELLED;
        terminal(now, "RUN_CANCELLED", "tool call was not executed because the agent run was cancelled");
    }

    synchronized boolean isTerminal() {
        return status.isTerminal();
    }

    String runId() {
        return runId;
    }

    AgentSpec spec() {
        return spec;
    }

    AgentThread thread() {
        return thread;
    }

    TranscriptStore transcriptStore() {
        return transcriptStore;
    }

    private AgentTurn currentTurn() {
        if (turns.isEmpty()) {
            throw new IllegalStateException("no current turn");
        }
        return turns.get(turns.size() - 1);
    }

    private void replaceCurrentTurn(AgentTurn turn) {
        turns.set(turns.size() - 1, turn);
    }

    private void exhaust(BudgetViolation violation, Instant now) {
        budgetViolation = violation;
        failureCode = "BUDGET_" + violation.name();
        failureMessage = "agent budget exhausted: " + violation;
        status = AgentRunStatus.BUDGET_EXHAUSTED;
        terminal(
                now,
                "BUDGET_" + violation.name(),
                "tool call was not executed because the agent budget was exhausted");
    }

    private void terminal(Instant now, String pendingToolCode, String pendingToolMessage) {
        closePendingToolCalls(pendingToolCode, pendingToolMessage, now);
        endedAt = now;
        touch(now);
        emitRunTerminal(now);
    }

    private void closePendingToolCalls(String code, String message, Instant now) {
        while (pendingToolIndex < pendingToolCalls.size()) {
            ToolCall call = pendingToolCalls.get(pendingToolIndex++);
            ToolResult result = ToolResult.cancelled(call.callId(), call.toolName(), code, message);
            transcriptStore.append(thread.threadId(), AgentMessage.tool(result, now));
            emitToolResult(result, null, now);
        }
        pendingToolCalls = List.of();
        pendingToolIndex = 0;
    }

    private void touch(Instant now) {
        updatedAt = now;
    }

    private void emitToolResult(ToolResult result, String executionId, Instant now) {
        emit(AgentEventType.TOOL_CALL_COMPLETED, now, currentTurn().turnNumber(), result.callId(), result.toolName(),
                attributes(
                        "status", result.status().name(),
                        "errorCode", result.errorCode(),
                        "executionId", executionId));
    }

    private void emitRunTerminal(Instant now) {
        AgentEventType type = switch (status) {
            case COMPLETED -> AgentEventType.RUN_COMPLETED;
            case INTERRUPTED -> AgentEventType.RUN_INTERRUPTED;
            case FAILED -> AgentEventType.RUN_FAILED;
            case CANCELLED -> AgentEventType.RUN_CANCELLED;
            case BUDGET_EXHAUSTED -> AgentEventType.RUN_BUDGET_EXHAUSTED;
            default -> throw new IllegalStateException("non-terminal AgentRun status: " + status);
        };
        emit(type, now, eventTurnNumber(), null, null, attributes(
                "status", status.name(),
                "turns", turns.size(),
                "toolCalls", toolCalls,
                "inputTokens", inputTokens,
                "outputTokens", outputTokens,
                "failureCode", failureCode,
                "failureMessage", failureMessage,
                "budgetViolation", budgetViolation == null ? null : budgetViolation.name()));
    }

    private int eventTurnNumber() {
        return turns.isEmpty() ? 0 : turns.get(turns.size() - 1).turnNumber();
    }

    private void emit(
            AgentEventType type,
            Instant now,
            int turnNumber,
            String operationId,
            String operationName,
            Map<String, Object> attributes
    ) {
        AgentEvent event = new AgentEvent(
                ++eventSequence,
                type,
                runId,
                recipeId,
                spec.agentId(),
                thread.threadId(),
                now,
                turnNumber,
                operationId,
                operationName,
                attributes);
        try {
            eventSink.publish(event);
        } catch (RuntimeException ignored) {
            // Observation must never alter AgentRun behavior.
        }
    }

    private static Map<String, Object> attributes(Object... keyValues) {
        LinkedHashMap<String, Object> selected = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null) {
                selected.put((String) keyValues[i], value);
            }
        }
        return Map.copyOf(selected);
    }

    private Map<String, Object> mergedToolMetadata() {
        if (thread.metadata().isEmpty()) {
            return spec.metadata();
        }
        if (spec.metadata().isEmpty()) {
            return thread.metadata();
        }
        // Agent metadata supplies defaults; the more specific thread metadata wins.
        java.util.LinkedHashMap<String, Object> merged = new java.util.LinkedHashMap<>(spec.metadata());
        merged.putAll(thread.metadata());
        return Map.copyOf(merged);
    }

    private static String failureMessage(Throwable failure) {
        if (failure == null) {
            return "unknown failure";
        }
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
