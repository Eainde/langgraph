public class ExecutionPersistingListener implements AgentListener {

    private static final Logger log = LoggerFactory.getLogger(ExecutionPersistingListener.class);

    private final OracleAgentExecutionStore executionStore;
    private final Map<Object, AtomicInteger> invocationCounters = new ConcurrentHashMap<>();
    private final Map<String, String> ongoingExecutions = new ConcurrentHashMap<>();
    private final Map<String, Instant> startTimes = new ConcurrentHashMap<>();

    public ExecutionPersistingListener(OracleAgentExecutionStore executionStore) {
        this.executionStore = executionStore;
    }

    @Override
    public void beforeAgentInvocation(AgentRequest agentRequest) {
        try {
            String agentName = agentRequest.agentName();
            Object memoryId = extractMemoryId(agentRequest.agenticScope());
            String trackingKey = buildTrackingKey(agentName, memoryId);

            // computeIfAbsent guarantees the lambda runs ONLY ONCE per key
            // Second call from inherited listener gets existing value, no DB write
            ongoingExecutions.computeIfAbsent(trackingKey, k -> {
                String executionId = UUID.randomUUID().toString();
                startTimes.put(trackingKey, Instant.now());

                AgentExecutionRecord record = AgentExecutionRecord.running(
                        executionId,
                        agentName,
                        memoryId != null ? memoryId.toString() : null,
                        agentName,
                        getNextOrder(memoryId),
                        safeGetInputs(agentRequest)
                );

                executionStore.insertRunning(record);
                log.debug("Agent execution started: agent={}, executionId={}", agentName, executionId);
                return executionId;
            });
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

            // get, not remove — so the second after call is harmless
            String executionId = ongoingExecutions.get(trackingKey);
            if (executionId == null) return;

            // UPDATE is idempotent — second call just overwrites with same data
            executionStore.markSuccess(executionId, agentResponse.output(), Instant.now());
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

            String executionId = ongoingExecutions.get(trackingKey);
            if (executionId == null) return;

            executionStore.markFailed(executionId,
                    buildErrorMessage(agentInvocationError.error()), Instant.now());
            log.debug("Agent execution failed: agent={}, executionId={}", agentName, executionId);
        } catch (Exception e) {
            log.warn("Failed to persist agent execution error", e);
        }
    }

    /**
     * Clean up tracking maps when scope is destroyed.
     */
    @Override
    public void beforeAgenticScopeDestroyed(AgenticScope agenticScope) {
        if (agenticScope instanceof DefaultAgenticScope defaultScope) {
            Object memoryId = defaultScope.memoryId();
            String prefix = (memoryId != null ? memoryId.toString() : "ephemeral") + "::";
            ongoingExecutions.keySet().removeIf(k -> k.startsWith(prefix));
            startTimes.keySet().removeIf(k -> k.startsWith(prefix));
            invocationCounters.remove(memoryId);
        }
    }

    @Override
    public boolean inheritedBySubagents() {
        return true;
    }

    private String buildTrackingKey(String agentName, Object memoryId) {
        return (memoryId != null ? memoryId.toString() : "ephemeral") + "::" + agentName;
    }

    // ... rest of helpers stay the same
}