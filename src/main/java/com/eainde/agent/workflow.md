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



  Their Architecture: YAML DAG Generator (Single-Tier Declarative)
  Pros:

  Low barrier to entry — non-engineers can author workflows in YAML
  Fast prototyping for simple linear or shallow branching flows
  Consistent graph structure, easy to visualise statically
  subgraph node provides some composability

  Cons (and this is where you push back hard):
  1. Reasoning is a black box at the node level. Each prompt_llm node is a single LLM call. There's no structured multi-step reasoning inside a node — no chain-of-thought execution, no critic-validator loops, no sequential extraction pipeline. For KYC/compliance use cases (like your CSM extraction with 9 sequential reasoning steps), you'd need 9 separate graph nodes with all the wiring YAML overhead. The graph explodes in complexity.
  2. No separation between orchestration and reasoning concerns. Business flow logic (should_loop, routing) and LLM reasoning logic live in the same flat spec. This violates separation of concerns — changing a reasoning strategy (e.g., switching from CoT to ReAct) forces YAML graph surgery.
  3. Python callables are escape hatches, not first-class citizens. Their python node type and callable_bool predicates require external Python functions — meaning anything non-trivial falls out of the declarative model. Your team ends up maintaining both YAML and scattered Python callables with no unified abstraction.
  4. Extremely limited for compound agent behaviour. The spec has no concept of retry logic, backoff, partial failure recovery, or checkpoint-based resumption within a node. Your WorkflowEngine with flowId-as-threadId checkpoint resumption simply cannot be expressed here.
  5. Observability is shallow. There's no mention of per-node agent execution tracking, Langfuse trace integration, or Micrometer metrics. Their trace_workflow() call in app.py is a single coarse trace — yours instruments at individual agent invocation level.

  Your Two-Tier Architecture: LangGraph4j + LangChain4j
  Pros (your justification points):
  1. Right abstraction at each tier.

  LangGraph4j handles what happens and in what order — macro flow, human-in-the-loop, checkpointing, parallelism, state management.
  LangChain4j handles how the LLM reasons — sequential chains, tool use, critic loops, retries. Each concern is isolated and independently evolvable.

  2. Nodes can be arbitrarily powerful. A single LangGraph4j node wrapping a 9-step LangChain4j sequenceBuilder chain (as in your CSM extraction pipeline) appears as one clean node to the graph. In the YAML model this would be 9 nodes with 8 edges — a maintenance nightmare and impossible to encapsulate.
  3. Enterprise-grade resilience is a first-class feature. Your retry infrastructure with exponential backoff, selective exception handling, and checkpoint-based resumption is baked into the framework — not bolted on as Python callables. Banking compliance workflows cannot lose state on LLM failure.
  4. Non-AI nodes are a proper pattern. Your AbstractNonAiUntypedAgent / AgentFactory abstraction means non-LLM steps (document enrichment, DB lookups) integrate seamlessly into the same chain without breaking the sequence contract. Their python node is a leaky abstraction in comparison.
  5. Observability is deep and structured. ExecutionPersistingListener → Oracle audit trail, Langfuse tracing per agent invocation, Micrometer metrics — all integrated as first-class framework concerns, not afterthoughts.
  6. Prompt management is decoupled. LangfusePromptService externalises prompts with versioning — in the YAML model, callable prompt references are hardcoded import paths. Changing a prompt means a code/YAML deployment.
  Cons to acknowledge honestly (so you're credible):

  Higher learning curve for new developers compared to writing YAML
  Requires Java/Spring Boot expertise — the YAML approach theoretically allows Python-native teams to contribute
  More boilerplate per agent (mitigated by your nexus-ai abstractions, but still real)


  The Killer Argument
  The YAML DAG generator is designed for shallow, prompt-chain workflows — think chatbots, simple Q&A, single-step document summarisation. Your KYC/compliance domain requires deep, compound reasoning with enterprise reliability guarantees. The moment their architecture hits a real compliance workflow — multi-document CSM extraction, eligibility classification with override logic, checkpoint recovery after a Gemini API timeout — the YAML model requires them to either: (a) explode the graph into dozens of nodes, or (b) hide complexity in python callables, at which point they've reinvented your architecture but less cleanly.
  Your two-tier approach is essentially what they'd evolve into naturally once their workflows get complex. You've just built the right abstraction from the start.





  Strengths

  Workflow authoring becomes configuration — teams could theoretically define new flows without Java code
  Consistency — all workflows follow the same structural contract, easier to audit and version YAML files
  Visualisation — a YAML spec can be rendered as a graph diagram automatically
  Lower barrier for simple workflows — a 3-node linear flow takes 20 lines of YAML

  Weaknesses — and these are significant for your context
  1. You're building a framework on top of a framework.
  LangGraph4j already IS your DAG executor. Building a YAML interpreter that then drives LangGraph4j adds an entire translation layer — YAML → Java DAG model → LangGraph4j graph. Every LangGraph4j feature (conditional edges, parallel nodes, checkpointing) needs to be re-exposed through your YAML schema. You'll spend months building the framework before writing a single business workflow.
  2. String-based references destroy type safety.
  The callable pattern ("projects.my_project.predicates:should_loop") means all your agent wiring is strings. Refactoring a class name? No compiler error — just a runtime ClassNotFoundException in production. This is a massive regression from where you are today.
  3. Complex reasoning nodes don't fit the model.
  Your CSM 9-step chain cannot be expressed as a single YAML node cleanly. You'd either expose it as a subgraph YAML (defeating the simplicity argument) or as a callable (hiding it behind a string reference, back to square one). The YAML model fundamentally assumes each node is one LLM call.
  4. Debugging becomes harder.
  When a workflow fails today, you have a full Spring stack trace, Langfuse trace, and Oracle audit log pointing to the exact agent and step. With YAML-driven execution, the stack trace points into your interpreter runtime — not into the business logic. Diagnosing failures in a compliance workflow becomes much harder.
  5. YAML has no business logic expressiveness.
  Retry policies, backoff strategies, partial failure recovery, conditional prompt selection based on document type — none of this can live in YAML cleanly. It all falls back to callable references, meaning you maintain Java and YAML simultaneously for every complex workflow.
  6. The operational overhead is real.
  YAML files need versioning, validation, schema enforcement, deployment pipelines, environment-specific overrides. For a bank, this means InfoSec review of YAML schema changes alongside code reviews. You've added a second artifact type with all the governance overhead of code but fewer of the safety guarantees.