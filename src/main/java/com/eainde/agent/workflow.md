// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// RESUMPTION METHODS
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/**
 * Resumes a previously failed workflow from its last successful checkpoint.
 * <p>
 * The checkpointer automatically determines the resume point — execution
 * continues from the node AFTER the last successfully checkpointed node.
 * </p>
 *
 * @param flowId    The original flowId (threadId) of the failed workflow.
 * @param beanName  The graph bean name (must match the original workflow).
 * @param inputs    The original (or corrected) inputs for the workflow.
 *                  These are passed to graph.invoke() but the checkpointer
 *                  will merge/override with the checkpointed state.
 * @return Optional containing the final state, or empty if graph returns null.
 */
public <S extends AgentState> Optional<S> resumeWorkflowSync(
        String flowId,
        String beanName,
        Map<String, Object> inputs) {
    return resumeWorkflowSync(flowId, beanName, inputs, null);
}

/**
 * Resumes a previously failed workflow from a SPECIFIC checkpoint.
 * <p>
 * Use this when you want precise control over the resume point — e.g.,
 * rewinding to an earlier node, not just the last successful one.
 * </p>
 *
 * @param flowId       The original flowId (threadId) of the failed workflow.
 * @param beanName     The graph bean name.
 * @param inputs       The original (or corrected) inputs.
 * @param checkpointId Specific checkpoint to resume from (null = latest).
 */
public <S extends AgentState> Optional<S> resumeWorkflowSync(
        String flowId,
        String beanName,
        Map<String, Object> inputs,
        String checkpointId) {

    WorkflowContext<S> workflowContext = setupResumeWorkflow(
            flowId, beanName, inputs, checkpointId);
    return invokeWorkflowSync(inputs, workflowContext);
}

/**
 * Async variant of resume.
 */
public <S extends AgentState> WorkflowHandler resumeWorkflowAsync(
        String flowId,
        String beanName,
        Map<String, Object> inputs,
        String checkpointId) {

    WorkflowContext<S> workflowContext = setupResumeWorkflow(
            flowId, beanName, inputs, checkpointId);
    return invokeWorkflowAsync(inputs, workflowContext);
}

/**
 * Sets up the workflow context for resumption WITHOUT creating a new AiWorkflowRun.
 * Reuses the original threadId so the checkpointer reconnects to the
 * existing checkpoint chain.
 */
@SuppressWarnings("unchecked")
private <S extends AgentState> WorkflowContext<S> setupResumeWorkflow(
        String flowId,
        String beanName,
        Map<String, Object> inputs,
        String checkpointId) {

    if (flowId == null || flowId.trim().isEmpty()) {
        throw new IllegalArgumentException("flowId cannot be null or empty for resume.");
    }
    if (beanName == null || beanName.trim().isEmpty()) {
        throw new IllegalArgumentException("Workflow beanName cannot be null or empty.");
    }

    CompiledGraph<S> graph = (CompiledGraph<S>) registry.get(beanName);
    if (graph == null) {
        throw new IllegalArgumentException("No workflow found with name: " + beanName);
    }

    // Look up the EXISTING failed workflow run to link the retry
    // Option A: Update existing run's status back to STARTED
    // Option B: Create a new run with a parentFlowId reference
    // Going with Option A for simplicity — you can switch to B for full audit trail
    AiWorkflow existingRun = nexusStoreProvider.workflows()
            .findByFlowId(flowId)
            .orElseThrow(() -> new IllegalArgumentException(
                    "No previous workflow run found for flowId: " + flowId));

    AiWorkflow updatedRun = updateAiWorkflowRun(existingRun, AiWorkflowStatus.RESTARTED);

    log.info("Resuming workflow. FlowId: {}, Bean: {}, CheckpointId: {}",
            flowId, beanName, checkpointId != null ? checkpointId : "LATEST");

    // *** THIS IS THE KEY LINE ***
    // Same threadId = checkpointer loads existing checkpoint chain
    var configBuilder = RunnableConfig.builder()
            .threadId(flowId)                          // <-- reuse original threadId
            .putMetadata("THREAD_NAME", beanName)
            .putMetadata("RESUME", "true")
            .putMetadata("RESUME_TIMESTAMP",
                    LocalDateTime.now().toString());

    // Optional: pin to a specific checkpoint (for "rewind to Node X")
    if (checkpointId != null && !checkpointId.trim().isEmpty()) {
        configBuilder.checkPointId(checkpointId);      // <-- LangGraph4j config key
    }

    RunnableConfig config = configBuilder.build();

    return new WorkflowContext<>(graph, updatedRun, config);
}
```

## What Happens Under the Hood
```
ORIGINAL RUN (flowId = "abc-123"):
  Node1 ✅  → checkpoint saved {threadId:"abc-123", next:Node2}
  Node2 ✅  → checkpoint saved {threadId:"abc-123", next:Node3}
  Node3 ❌  → no checkpoint (exception thrown)
  Status → FAILED

RESUME CALL: resumeWorkflowSync("abc-123", "kycWorkflow", inputs)
  ┌─ RunnableConfig built with threadId="abc-123"
  ├─ graph.invoke(inputs, config)
  ├─ Checkpointer sees threadId="abc-123"
  ├─ Loads latest checkpoint → state after Node2, next=Node3
  ├─ Executes Node3 → Node4 → Node5
  └─ Status → FINISHED