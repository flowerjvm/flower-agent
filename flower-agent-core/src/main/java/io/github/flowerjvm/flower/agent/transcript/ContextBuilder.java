package io.github.flowerjvm.flower.agent.transcript;

import io.github.flowerjvm.flower.agent.model.AgentMessage;
import io.github.flowerjvm.flower.agent.run.AgentRun;
import io.github.flowerjvm.flower.agent.run.AgentThread;

import java.util.List;

/**
 * Selects already-available transcript messages for the next model turn.
 *
 * <p>This callback runs on a Flower Worker tick. Implementations must be
 * bounded, in-memory, and non-blocking. They must not perform RAG lookup,
 * database access, HTTP, model calls, tool calls, or other I/O. Retrieve
 * external context before the run or through an explicit asynchronous Step or
 * AgentTool.</p>
 *
 * <p>The agent system prompt is supplied separately by {@code AgentSpec} and
 * is prepended after this callback. Implementations should return conversation
 * messages only.</p>
 */
@FunctionalInterface
public interface ContextBuilder {

    List<AgentMessage> build(AgentRun run, AgentThread thread, TranscriptStore transcriptStore);

    static ContextBuilder fullTranscript() {
        return (run, thread, store) -> store.messages(thread.threadId());
    }
}
