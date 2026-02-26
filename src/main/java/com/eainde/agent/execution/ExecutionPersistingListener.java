package com.eainde.agent.execution;

import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.scope.DefaultAgenticScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An {@link AgentListener} that persists every agent invocation to Oracle database.
 * <p>
 * Records are inserted with RUNNING status when an agent starts, then updated
 * to SUCCESS or FAILED when the agent completes or throws an error.
 * This ensures partial executions are visible in the database even if the
 * workflow crashes midway.
 * <p>
 * Must be registered with {@code inheritedBySubagents() = true} to capture
 * all sub-agent invocations in the workflow.
 * <p>
 * Usage:
 * <pre>
 * ExecutionPersistingListener listener = new ExecutionPersistingListener(executionStore, "my-pipeline");
 *
 * MyPipeline pipeline = AgenticServices.sequenceBuilder(MyPipeline.class)
 *         .chatModel(chatModel)
 *         .listener(listener)
 *         .subAgents(writer, editor)
 *         .build();
 * </pre>
 */
public class ExecutionPersistingListener implements AgentListener {

    private static final Logger log = LoggerFactory.getLogger(ExecutionPersistingListener.class);

    private final OracleAgentExecutionStore executionStore;
    private final String agentId;

    /**
     * Tracks the invocation order per memory (user session).
     * Key: memoryId, Value: counter
     */
    private final Map<Object, AtomicInteger> invocationCounters = new ConcurrentHashMap<>();

    /**
     * Tracks execution IDs for ongoing invocations so we can update them on completion.
     * Key: agentName + threadId (to handle parallel agents), Value: executionId
     */
    private final Map<String, String> ongoingExecutions = new ConcurrentHashMap<>();

    public ExecutionPersistingListener(OracleAgentExecutionStore executionStore, String agentId) {
        this.executionStore = executionStore;
        this.agentId = agentId;
    }

    @Override
    public void beforeAgentInvocation(AgentRequest agentRequest) {
        try {
            String executionId = UUID.randomUUID().toString();
            String agentName = agentRequest.agentName();
            Object memoryId = extractMemoryId(agentRequest.agenticScope());
            int order = getNextOrder(memoryId);

            // Track this execution so afterAgentInvocation/onError can find it
            String trackingKey = buildTrackingKey(agentName);
            ongoingExecutions.put(trackingKey, executionId);

            AgentExecutionRecord record = AgentExecutionRecord.running(
                    executionId,
                    agentId,
                    memoryId != null ? memoryId.toString() : null,
                    agentName,
                    order,
                    safeGetInputs(agentRequest)
            );

            executionStore.insertRunning(record);
            log.debug("Agent execution started: agent={}, executionId={}, order={}",
                    agentName, executionId, order);
        } catch (Exception e) {
            log.warn("Failed to persist agent execution start for agent: {}",
                    agentRequest.agentName(), e);
            // Don't let persistence failures break the workflow
        }
    }

    @Override
    public void afterAgentInvocation(AgentResponse agentResponse) {
        try {
            String agentName = agentResponse.agentName();
            String trackingKey = buildTrackingKey(agentName);
            String executionId = ongoingExecutions.remove(trackingKey);

            if (executionId == null) {
                log.warn("No tracked execution found for agent: {}", agentName);
                return;
            }

            Instant completedAt = Instant.now();
            Object output = agentResponse.output();

            executionStore.markSuccess(executionId, output, completedAt);
            log.debug("Agent execution completed: agent={}, executionId={}",
                    agentName, executionId);
        } catch (Exception e) {
            log.warn("Failed to persist agent execution completion for agent: {}",
                    agentResponse.agentName(), e);
        }
    }

    @Override
    public void onAgentInvocationError(AgentInvocationError agentInvocationError) {
        try {
            String agentName = agentInvocationError.agentName();
            String trackingKey = buildTrackingKey(agentName);
            String executionId = ongoingExecutions.remove(trackingKey);

            if (executionId == null) {
                log.warn("No tracked execution found for failed agent: {}", agentName);
                return;
            }

            Instant completedAt = Instant.now();
            Throwable error = agentInvocationError.error();
            String errorMessage = buildErrorMessage(error);

            executionStore.markFailed(executionId, errorMessage, completedAt);
            log.debug("Agent execution failed: agent={}, executionId={}, error={}",
                    agentName, executionId, error.getMessage());
        } catch (Exception e) {
            log.warn("Failed to persist agent execution error for agent: {}",
                    agentInvocationError.agentName(), e);
        }
    }

    /**
     * CRITICAL: Must return true so this listener captures ALL sub-agent
     * invocations in the workflow, not just the root agent.
     */
    @Override
    public boolean inheritedBySubagents() {
        return true;
    }

    /**
     * Resets the invocation counter for a specific memory/session.
     * Call this when a user session starts a new workflow execution.
     */
    public void resetCounter(Object memoryId) {
        invocationCounters.remove(memoryId);
    }

    // --- Private helpers ---

    private int getNextOrder(Object memoryId) {
        if (memoryId == null) {
            memoryId = "ephemeral";
        }
        return invocationCounters
                .computeIfAbsent(memoryId, k -> new AtomicInteger(0))
                .incrementAndGet();
    }

    private String buildTrackingKey(String agentName) {
        // Include thread ID to handle parallel agent executions safely
        return agentName + "-" + Thread.currentThread().threadId();
    }

    private Object extractMemoryId(AgenticScope agenticScope) {
        if (agenticScope instanceof DefaultAgenticScope defaultScope) {
            return defaultScope.memoryId();
        }
        return null;
    }

    private Map<String, Object> safeGetInputs(AgentRequest request) {
        try {
            return request.inputs();
        } catch (Exception e) {
            return Map.of("_error", "Failed to extract inputs: " + e.getMessage());
        }
    }

    private String buildErrorMessage(Throwable error) {
        if (error == null) return "Unknown error";
        StringBuilder sb = new StringBuilder();
        sb.append(error.getClass().getName()).append(": ").append(error.getMessage());

        // Include root cause if different
        Throwable cause = error.getCause();
        if (cause != null && cause != error) {
            sb.append(" | Caused by: ")
                    .append(cause.getClass().getName())
                    .append(": ")
                    .append(cause.getMessage());
        }
        return sb.toString();
    }
}
