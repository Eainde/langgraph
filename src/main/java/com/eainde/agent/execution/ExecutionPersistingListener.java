package com.eainde.agent.execution;

public class ExecutionPersistingListener implements AgentListener {

    private static final Logger log = LoggerFactory.getLogger(ExecutionPersistingListener.class);

    private final OracleAgentExecutionStore executionStore;

    // Remove agentId from constructor — it's now extracted per invocation
    private final Map<Object, AtomicInteger> invocationCounters = new ConcurrentHashMap<>();
    private final Map<String, String> ongoingExecutions = new ConcurrentHashMap<>();

    // Track agentId per execution for use in afterAgent/onError
    private final Map<String, String> executionAgentIds = new ConcurrentHashMap<>();

    public ExecutionPersistingListener(OracleAgentExecutionStore executionStore) {
        this.executionStore = executionStore;
    }

    @Override
    public void beforeAgentInvocation(AgentRequest agentRequest) {
        try {
            String executionId = UUID.randomUUID().toString();
            String agentName = agentRequest.agentName();
            Object memoryId = extractMemoryId(agentRequest.agenticScope());

            // Use the agent's own name as the agentId
            String agentId = agentName;

            int order = getNextOrder(memoryId);
            String trackingKey = buildTrackingKey(agentName);
            ongoingExecutions.put(trackingKey, executionId);
            executionAgentIds.put(executionId, agentId);

            AgentExecutionRecord record = AgentExecutionRecord.running(
                    executionId,
                    agentId,
                    memoryId != null ? memoryId.toString() : null,
                    agentName,
                    order,
                    safeGetInputs(agentRequest)
            );

            executionStore.insertRunning(record);
        } catch (Exception e) {
            log.warn("Failed to persist agent execution start for agent: {}",
                    agentRequest.agentName(), e);
        }
    }

    @Override
    public void afterAgentInvocation(AgentResponse agentResponse) {
        try {
            String trackingKey = buildTrackingKey(agentResponse.agentName());
            String executionId = ongoingExecutions.remove(trackingKey);
            executionAgentIds.remove(executionId);

            if (executionId == null) return;

            executionStore.markSuccess(executionId, agentResponse.output(), Instant.now());
        } catch (Exception e) {
            log.warn("Failed to persist agent execution completion", e);
        }
    }

    @Override
    public void onAgentInvocationError(AgentInvocationError agentInvocationError) {
        try {
            String trackingKey = buildTrackingKey(agentInvocationError.agentName());
            String executionId = ongoingExecutions.remove(trackingKey);
            executionAgentIds.remove(executionId);

            if (executionId == null) return;

            executionStore.markFailed(executionId, buildErrorMessage(agentInvocationError.error()), Instant.now());
        } catch (Exception e) {
            log.warn("Failed to persist agent execution error", e);
        }
    }

    @Override
    public boolean inheritedBySubagents() {
        return true;
    }

    // ... rest of the helper methods stay the same
}