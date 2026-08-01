package io.github.flowerjvm.flower.agent.transcript;

import io.github.flowerjvm.flower.agent.model.AgentMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory transcript store for transient runs and tests.
 */
public final class InMemoryTranscriptStore implements TranscriptStore {

    private final Map<String, List<AgentMessage>> transcripts = new ConcurrentHashMap<>();

    @Override
    public void append(String threadId, AgentMessage message) {
        requireThreadId(threadId);
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        List<AgentMessage> transcript = transcripts.computeIfAbsent(
                threadId,
                ignored -> new ArrayList<>());
        synchronized (transcript) {
            transcript.add(message);
        }
    }

    @Override
    public List<AgentMessage> messages(String threadId) {
        requireThreadId(threadId);
        List<AgentMessage> transcript = transcripts.get(threadId);
        if (transcript == null) {
            return List.of();
        }
        synchronized (transcript) {
            return List.copyOf(transcript);
        }
    }

    private static void requireThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must not be blank");
        }
    }
}

