package io.github.flowerjvm.flower.agent.flow;

import io.github.flowerjvm.flower.agent.model.ToolCall;
import io.github.flowerjvm.flower.agent.model.ToolResult;
import io.github.flowerjvm.flower.agent.model.ToolResultStatus;
import io.github.flowerjvm.flower.agent.tool.AgentTool;
import io.github.flowerjvm.flower.agent.tool.AgentToolExecution;
import io.github.flowerjvm.flower.agent.tool.ToolExecutionStatus;
import io.github.flowerjvm.flower.agent.tool.ToolRegistry;
import io.github.flowerjvm.flower.core.step.Step;
import io.github.flowerjvm.flower.core.step.StepContext;
import io.github.flowerjvm.flower.core.step.StepResult;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

final class ExecuteToolsStep extends Step {

    private final AgentRunSession session;
    private final ToolRegistry toolRegistry;
    private final String prepareContextStepId;
    private final String finalizeStepId;

    private ToolCall activeCall;
    private AgentToolExecution execution;
    private boolean completedExecution;

    ExecuteToolsStep(
            AgentRunSession session,
            ToolRegistry toolRegistry,
            String prepareContextStepId,
            String finalizeStepId
    ) {
        this.session = session;
        this.toolRegistry = toolRegistry;
        this.prepareContextStepId = prepareContextStepId;
        this.finalizeStepId = finalizeStepId;
    }

    @Override
    protected void onEnter(StepContext ctx) {
        clearExecution();
    }

    @Override
    protected StepResult onTick(StepContext ctx) {
        if (session.isTerminal()) {
            return StepResult.goTo(finalizeStepId);
        }
        if (activeCall == null) {
            activeCall = session.currentToolCall();
            if (activeCall == null) {
                session.finishToolBatch(now(ctx));
                return StepResult.goTo(prepareContextStepId);
            }
            if (!session.reserveToolCall(now(ctx))) {
                return StepResult.goTo(finalizeStepId);
            }
        }
        if (execution == null) {
            return startTool(ctx);
        }
        if (ctx.timedOut()) {
            return recordFailure(ctx, new TimeoutException("tool call timed out"));
        }

        ToolExecutionStatus executionStatus;
        try {
            executionStatus = execution.poll();
        } catch (Throwable failure) {
            return recordFailure(ctx, failure);
        }
        if (executionStatus == null) {
            return recordFailure(ctx, new IllegalStateException("tool execution returned null status"));
        }
        return switch (executionStatus) {
            case PENDING -> StepResult.stay();
            case READY -> recordReady(ctx);
            case FAILED -> recordFailure(ctx, execution.error());
            case CANCELLED -> recordFailure(ctx, new CancellationException("tool execution was cancelled"));
        };
    }

    @Override
    protected void onExit(StepContext ctx) {
        if (!completedExecution && execution != null) {
            cancelExecution();
            session.cancel("agent flow left a pending tool call", now(ctx));
        }
        clearExecution();
    }

    @Override
    protected void onReset(StepContext ctx) {
        clearExecution();
    }

    private StepResult startTool(StepContext ctx) {
        AgentTool tool = toolRegistry.find(activeCall.toolName()).orElse(null);
        if (tool == null) {
            return recordResult(ctx, ToolResult.failed(
                    activeCall.callId(),
                    activeCall.toolName(),
                    "UNKNOWN_TOOL",
                    "tool is not registered: " + activeCall.toolName()), null);
        }
        try {
            execution = tool.start(activeCall, session.toolContext());
            if (execution == null) {
                return recordFailure(ctx, new IllegalStateException("tool returned null execution handle"));
            }
            ctx.startTimeout(session.spec().toolTimeout().toMillis());
            completedExecution = false;
            return StepResult.stay();
        } catch (Throwable failure) {
            return recordFailure(ctx, failure);
        }
    }

    private StepResult recordReady(StepContext ctx) {
        ToolResult result;
        try {
            result = execution.result();
            if (result == null) {
                return recordFailure(ctx, new IllegalStateException("tool execution returned null result"));
            }
            if (!activeCall.callId().equals(result.callId())
                    || !activeCall.toolName().equals(result.toolName())) {
                return recordFailure(ctx, new IllegalStateException("tool result identity mismatch"));
            }
        } catch (Throwable failure) {
            return recordFailure(ctx, failure);
        }
        return recordResult(ctx, result, executionId());
    }

    private StepResult recordFailure(StepContext ctx, Throwable failure) {
        Throwable actual = failure == null ? new IllegalStateException("tool execution failed") : failure;
        String message = actual.getMessage() == null ? actual.getClass().getSimpleName() : actual.getMessage();
        String failedExecutionId = executionId();
        cancelExecution();
        return recordResult(ctx, ToolResult.failed(
                activeCall.callId(),
                activeCall.toolName(),
                "TOOL_EXECUTION_FAILED",
                message), failedExecutionId);
    }

    private StepResult recordResult(StepContext ctx, ToolResult result, String executionId) {
        completedExecution = true;
        session.recordToolResult(result, executionId, now(ctx));
        if (result.status() == ToolResultStatus.INTERRUPTED) {
            session.interrupt(
                    result.content(),
                    result.resumeToken(),
                    result.metadata().isEmpty() ? Map.of("toolCallId", result.callId()) : result.metadata(),
                    now(ctx));
            clearExecution();
            return StepResult.goTo(finalizeStepId);
        }
        clearExecution();
        return StepResult.stay();
    }

    private void clearExecution() {
        activeCall = null;
        execution = null;
        completedExecution = false;
    }

    private void cancelExecution() {
        if (execution == null) {
            return;
        }
        try {
            execution.cancel();
        } catch (Throwable ignored) {
            // Cancellation is best-effort; the failure is still recorded as a ToolResult.
        } finally {
            execution = null;
        }
    }

    private String executionId() {
        if (execution == null) {
            return null;
        }
        try {
            return execution.executionId();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Instant now(StepContext ctx) {
        return Instant.ofEpochMilli(ctx.clock().currentTimeMillis());
    }
}
