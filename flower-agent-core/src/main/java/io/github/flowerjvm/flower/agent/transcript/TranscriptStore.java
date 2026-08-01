package io.github.flowerjvm.flower.agent.transcript;

import io.github.flowerjvm.flower.agent.model.AgentMessage;

import java.util.List;

/**
 * Transcript persistence port.
 */
public interface TranscriptStore {

    void append(String threadId, AgentMessage message);

    List<AgentMessage> messages(String threadId);
}

