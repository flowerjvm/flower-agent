package io.github.flowerjvm.flower.agent.observability;

/** Correlation selected for one Agent run. */
public record AgentObservationCorrelation(String traceId, String parentRunId) {

    public AgentObservationCorrelation {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
        traceId = traceId.trim();
        parentRunId = clean(parentRunId);
    }

    public static AgentObservationCorrelation standalone(String runId) {
        return new AgentObservationCorrelation(runId, null);
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String selected = value.trim();
        return selected.isEmpty() ? null : selected;
    }
}
