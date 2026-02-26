package com.eainde.agent.execution;

public class ExecutionPersistingListener implements AgentListener {

    private static final Logger log = LoggerFactory.getLogger(ExecutionPersistingListener.class);

    private final OracleAgentExecutionStore executionStore;
    private final Map<Object, AtomicInteger> invocationCounters = new ConcurrentHashMap<>();
    private final Map<String, String> ongoingExecutions = new ConcurrentHashMap<>();
    private final Map<String, Instant> startTimes = new ConcurrentHashMap<>();

    // Tracks current depth per agent+memory
    private final Map<String, AtomicInteger> callDepth = new ConcurrentHashMap<>();

    public ExecutionPersistingListener(OracleAgentExecutionStore executionStore) {
        this.executionStore = executionStore;
    }

    @Override
    public void beforeAgentInvocation(AgentRequest agentRequest) {
        try {
            String agentName = agentRequest.agentName();
            Object memoryId = extractMemoryId(agentRequest.agenticScope());
            String baseKey = buildBaseKey(agentName, memoryId);

            int depth = callDepth
                    .computeIfAbsent(baseKey, k -> new AtomicInteger(0))
                    .incrementAndGet();

            // Each depth gets its own tracking key → its own row
            String trackingKey = baseKey + "::depth-" + depth;
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
            log.debug("Agent execution started: agent={}, depth={}, executionId={}",
                    agentName, depth, executionId);
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
            String baseKey = buildBaseKey(agentName, memoryId);

            AtomicInteger depth = callDepth.get(baseKey);
            if (depth == null) return;

            int currentDepth = depth.getAndDecrement();
            if (currentDepth <= 0) {
                callDepth.remove(baseKey);
                return;
            }

            String trackingKey = baseKey + "::depth-" + currentDepth;
            String executionId = ongoingExecutions.remove(trackingKey);
            startTimes.remove(trackingKey);

            if (currentDepth == 0) callDepth.remove(baseKey);
            if (executionId == null) return;

            executionStore.markSuccess(executionId, agentResponse.output(), Instant.now());
            log.debug("Agent execution completed: agent={}, depth={}, executionId={}",
                    agentName, currentDepth, executionId);
        } catch (Exception e) {
            log.warn("Failed to persist agent execution completion", e);
        }
    }

    @Override
    public void onAgentInvocationError(AgentInvocationError agentInvocationError) {
        try {
            String agentName = agentInvocationError.agentName();
            Object memoryId = extractMemoryId(agentInvocationError.agenticScope());
            String baseKey = buildBaseKey(agentName, memoryId);

            AtomicInteger depth = callDepth.get(baseKey);
            if (depth == null) return;

            int currentDepth = depth.getAndDecrement();
            if (currentDepth <= 0) {
                callDepth.remove(baseKey);
                return;
            }

            String trackingKey = baseKey + "::depth-" + currentDepth;
            String executionId = ongoingExecutions.remove(trackingKey);
            startTimes.remove(trackingKey);

            if (currentDepth == 0) callDepth.remove(baseKey);
            if (executionId == null) return;

            executionStore.markFailed(executionId,
                    buildErrorMessage(agentInvocationError.error()), Instant.now());
            log.debug("Agent execution failed: agent={}, depth={}, executionId={}",
                    agentName, currentDepth, executionId);
        } catch (Exception e) {
            log.warn("Failed to persist agent execution error", e);
        }
    }

    @Override
    public boolean inheritedBySubagents() {
        return true;
    }

    private String buildBaseKey(String agentName, Object memoryId) {
        return (memoryId != null ? memoryId.toString() : "ephemeral") + "::" + agentName;
    }

    // ... rest of helpers stay the same
}