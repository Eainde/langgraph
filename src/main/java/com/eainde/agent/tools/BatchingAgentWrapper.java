package com.eainde.agent.tools;

import com.db.clm.kyc.ai.config.AgentFactory;
import com.db.clm.kyc.ai.config.AgentSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agentic.ResultWithAgenticScope;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.scope.AgenticScope;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wraps an {@link AgentSpec} to add automatic LLM-driven pagination (batching)
 * for agents that may produce output exceeding the model's output token limit.
 *
 * <p>Implements {@link UntypedAgent} so it fits directly into
 * {@code agentFactory.sequence()} and {@code agentFactory.parallel()}
 * alongside regular agents and other wrappers like {@code Wave5MergerAgent}.</p>
 *
 * <h3>How it works:</h3>
 * <pre>
 * invoke(input)
 *   │
 *   ├── 1. Create fresh BatchAccumulatorTool
 *   ├── 2. Build a NEW AgentSpec = original spec + tool added
 *   ├── 3. agentFactory.create(specWithTool) — standard create, no changes to AgentFactory
 *   ├── 4. agent.invoke(input) — LangChain4j tool loop runs automatically
 *   │       └── Small output → LLM returns JSON directly (tool unused)
 *   │       └── Large output → LLM calls submit_batch × N → returns summary
 *   ├── 5. Check tool.wasUsed()
 *   │       ├── YES → return tool.getMergedResult() (replaces summary with real data)
 *   │       └── NO  → return raw agent output (pass-through)
 *   └── 6. Tool + agent garbage collected
 * </pre>
 *
 * <h3>Why the wrapper is needed (even though tool is on the spec):</h3>
 * <ol>
 *   <li><b>Output replacement:</b> When the LLM uses the tool, agent.invoke() returns
 *       a summary string ("Extraction complete. 130 records submitted."), NOT the actual
 *       data. The wrapper replaces this with the merged records from the tool.</li>
 *   <li><b>Fresh state:</b> A new tool instance is created per invocation, so there's
 *       no stale data from previous requests.</li>
 * </ol>
 *
 * <h3>Usage:</h3>
 * <pre>
 * agentFactory.sequence("rawNames",
 *     new BatchingAgentWrapper(agentFactory, CANDIDATE_EXTRACTOR_SPEC, objectMapper),
 *     new BatchingAgentWrapper(agentFactory, SOURCE_CLASSIFIER_SPEC, objectMapper),
 *     new BatchingAgentWrapper(agentFactory, NAME_NORMALIZER_SPEC, objectMapper));
 * </pre>
 */
@Log4j2
public class BatchingAgentWrapper implements UntypedAgent {

    private final AgentFactory agentFactory;
    private final AgentSpec baseSpec;
    private final ObjectMapper objectMapper;

    /**
     * @param agentFactory factory to create agents (standard create method, no changes)
     * @param baseSpec     the original AgentSpec (without the batch tool)
     * @param objectMapper shared Jackson mapper for batch merging
     */
    public BatchingAgentWrapper(AgentFactory agentFactory,
                                AgentSpec baseSpec,
                                ObjectMapper objectMapper) {
        this.agentFactory = agentFactory;
        this.baseSpec = baseSpec;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    //  UntypedAgent API
    // =========================================================================

    @Override
    public Object invoke(Map<String, Object> input) {
        String agentName = baseSpec.getAgentName();
        log.debug("[{}] BatchingAgentWrapper — starting invocation", agentName);

        // ── Step 1: Fresh tool for this invocation ──────────────────────
        BatchAccumulatorTool tool = new BatchAccumulatorTool(objectMapper);

        // ── Step 2: Build new spec with tool added ──────────────────────
        AgentSpec specWithTool = buildSpecWithTool(tool);

        // ── Step 3: Create fresh agent from modified spec ───────────────
        UntypedAgent agent = agentFactory.create(specWithTool);

        // ── Step 4: Invoke the agent ────────────────────────────────────
        // LangChain4j sees the @Tool method on BatchAccumulatorTool,
        // enters its automatic tool execution loop:
        //   LLM response → tool_call → execute tool → result to LLM → repeat
        //   Until LLM returns final text (no more tool_calls)
        Object rawResult = agent.invoke(input);

        // ── Step 5: Check if batching happened ──────────────────────────
        return resolveResult(agentName, tool, rawResult);
    }

    @Override
    public ResultWithAgenticScope<String> invokeWithAgenticScope(Map<String, Object> input) {
        String agentName = baseSpec.getAgentName();
        log.debug("[{}] BatchingAgentWrapper — starting invocation (with scope)", agentName);

        // ── Step 1: Fresh tool ──────────────────────────────────────────
        BatchAccumulatorTool tool = new BatchAccumulatorTool(objectMapper);

        // ── Step 2: Build new spec with tool ────────────────────────────
        AgentSpec specWithTool = buildSpecWithTool(tool);

        // ── Step 3: Create fresh agent ──────────────────────────────────
        UntypedAgent agent = agentFactory.create(specWithTool);

        // ── Step 4: Invoke with scope access ────────────────────────────
        ResultWithAgenticScope<String> result = agent.invokeWithAgenticScope(input);

        // ── Step 5: Merge if batching happened ──────────────────────────
        if (tool.wasUsed()) {
            String mergedResult = tool.getMergedResult();
            log.info("[{}] Batching used — {} batches, {} total records merged (with scope)",
                    agentName, tool.getBatchCount(), tool.getTotalRecordCount());
            return new ResultWithAgenticScope<>(result.agenticScope(), mergedResult);
        }

        log.debug("[{}] No batching needed (with scope)", agentName);
        return result;
    }

    // =========================================================================
    //  AgenticScopeAccess delegation
    // =========================================================================

    @Override
    public AgenticScope getAgenticScope(Object memoryId) {
        return null;
    }

    @Override
    public boolean evictAgenticScope(Object memoryId) {
        return false;
    }

    // =========================================================================
    //  Internal Helpers
    // =========================================================================

    /**
     * Builds a new AgentSpec that includes the batch accumulator tool,
     * merged with any tools already on the base spec.
     *
     * <p>Uses {@code baseSpec.toBuilder()} to copy all existing configuration
     * (prompt, guardrails, inputs, outputs, listeners, etc.) and adds the tool.</p>
     */
    private AgentSpec buildSpecWithTool(BatchAccumulatorTool tool) {
        // Merge existing spec tools + the batch tool
        List<Object> mergedTools = new ArrayList<>();

        if (baseSpec.hasTools()) {
            mergedTools.addAll(baseSpec.getTools());
        }

        mergedTools.add(tool);

        return baseSpec.toBuilder()
                .tools(mergedTools)
                .build();
    }

    /**
     * Resolves the final result: if the tool was used, returns merged batch data;
     * otherwise returns the raw agent output as-is.
     */
    private Object resolveResult(String agentName, BatchAccumulatorTool tool, Object rawResult) {
        if (tool.wasUsed()) {
            String mergedResult = tool.getMergedResult();
            log.info("[{}] Batching used — {} batches, {} total records merged",
                    agentName, tool.getBatchCount(), tool.getTotalRecordCount());
            return mergedResult;
        }

        log.debug("[{}] No batching needed — output returned directly", agentName);
        return rawResult;
    }

    // =========================================================================
    //  Accessors (for debugging/testing)
    // =========================================================================

    public AgentSpec getBaseSpec() {
        return baseSpec;
    }

    public String getAgentName() {
        return baseSpec.getAgentName();
    }
}