package com.eainde.agent.execution;

import java.time.Instant;

/**
 * Represents a single agent invocation record for audit/debugging purposes.
 */
public record AgentExecutionRecord(
        String executionId,
        String agentId,
        String memoryId,
        String agentName,
        int invocationOrder,
        String status,
        Object inputData,
        Object outputData,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        long durationMs
) {
    /**
     * Creates a new RUNNING record when an agent invocation starts.
     */
    public static AgentExecutionRecord running(
            String executionId,
            String agentId,
            String memoryId,
            String agentName,
            int invocationOrder,
            Object inputData) {
        return new AgentExecutionRecord(
                executionId,
                agentId,
                memoryId,
                agentName,
                invocationOrder,
                "RUNNING",
                inputData,
                null,
                null,
                Instant.now(),
                null,
                0
        );
    }
}
