package com.eainde.agent.execution;

public class ExecutionPersistingListener implements AgentListener {

    private static final Logger log = LoggerFactory.getLogger(ExecutionPersistingListener.class);

    private final OracleAgentExecutionStore executionStore;
    private final Map<Object, AtomicInteger> invocationCounters = new ConcurrentHashMap<>();
    private final Map<String, String> ongoingExecutions = new ConcurrentHashMap<>();
    private final Map<String, Instant> startTimes = new ConcurrentHashMap<>();

    // Tracks how many times before has fired for a given key without a matching after
    private final Map<String, AtomicInteger> callDepth = new ConcurrentHashMap<>();

    public ExecutionPersistingListener(OracleAgentExecutionStore executionStore) {
        this.executionStore = executionStore;
    }

    @Override
    public void beforeAgentInvocation(AgentRequest agentRequest) {
        try {
            String agentName = agentRequest.agentName();
            Object memoryId = extractMemoryId(agentRequest.agenticScope());
            String trackingKey = buildTrackingKey(agentName, memoryId);

            int depth = callDepth
                    .computeIfAbsent(trackingKey, k -> new AtomicInteger(0))
                    .incrementAndGet();

            // Only INSERT on the first before (depth == 1)
            // Subsequent duplicate calls from inheritance just increment the counter
            if (depth > 1) {
                return;
            }

            String executionId = UUID.randomUUID().toString();
            ongoingExecutions.put(trackingKey, executionId);
            startTimes.put(trackingKey, Instant.now());

            int order = getNextOrder(memoryId);

            AgentExecutionRecord record = AgentExecutionRecord.running(
                    executionId,
                    agentName,
                    memoryId != null ? memoryId.toString() : null,
                    agentName,
                    order,
                    safeGetInputs(agentRequest)
            );

            executionStore.insertRunning(record);
            log.debug("Agent execution started: agent={}, executionId={}", agentName, executionId);
        } catch (Exception e) {
            log.warn("Failed to persist agent execution start for agent: {}",
                    agentRequest.agentName(), e);
        }
    }

    @Override
    public void afterAgentInvocation(AgentResponse agentResponse) {
        try {
            String agentName = agentResponse.agentName();
            Object memoryId = extractMemoryId(agentResponse.agenticScope());
            String trackingKey = buildTrackingKey(agentName, memoryId);

            AtomicInteger depth = callDepth.get(trackingKey);
            if (depth == null) return;

            int remaining = depth.decrementAndGet();

            // Only UPDATE on the last after (depth back to 0)
            if (remaining > 0) {
                return;
            }

            callDepth.remove(trackingKey);
            String executionId = ongoingExecutions.remove(trackingKey);
            Instant startedAt = startTimes.remove(trackingKey);

            if (executionId == null) return;

            Instant completedAt = Instant.now();
            executionStore.markSuccess(executionId, agentResponse.output(), completedAt);
            log.debug("Agent execution completed: agent={}, executionId={}", agentName, executionId);
        } catch (Exception e) {
            log.warn("Failed to persist agent execution completion", e);
        }
    }

    @Override
    public void onAgentInvocationError(AgentInvocationError agentInvocationError) {
        try {
            String agentName = agentInvocationError.agentName();
            Object memoryId = extractMemoryId(agentInvocationError.agenticScope());
            String trackingKey = buildTrackingKey(agentName, memoryId);

            AtomicInteger depth = callDepth.get(trackingKey);
            if (depth == null) return;

            int remaining = depth.decrementAndGet();

            if (remaining > 0) {
                return;
            }

            callDepth.remove(trackingKey);
            String executionId = ongoingExecutions.remove(trackingKey);
            startTimes.remove(trackingKey);

            if (executionId == null) return;

            Instant completedAt = Instant.now();
            executionStore.markFailed(executionId, buildErrorMessage(agentInvocationError.error()), completedAt);
            log.debug("Agent execution failed: agent={}, executionId={}", agentName, executionId);
        } catch (Exception e) {
            log.warn("Failed to persist agent execution error", e);
        }
    }

    @Override
    public boolean inheritedBySubagents() {
        return true;
    }

    // ... rest of helpers stay the same
}