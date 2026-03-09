package com.eainde.agent.tools;

import com.db.clm.kyc.ai.config.AgentFactory;
import com.db.clm.kyc.ai.config.AgentSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agentic.ResultWithAgenticScope;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.scope.AgenticScope;
import lombok.extern.log4j.Log4j2;

import java.util.Map;

/**
 * Wraps an {@link AgentSpec} to add automatic LLM-driven pagination (batching)
 * for agents that may produce output exceeding the model's output token limit.
 *
 * <p>Implements {@link UntypedAgent} so it fits directly into
 * {@code agentFactory.sequence()} alongside regular agents and other wrappers
 * like {@code Wave5MergerAgent}.</p>
 *
 * <h3>How it works:</h3>
 * <pre>
 * invoke(input)
 *   │
 *   ├── 1. Create fresh BatchAccumulatorTool (new POJO, not shared)
 *   ├── 2. Create fresh agent from spec WITH this tool instance
 *   ├── 3. Invoke agent (LangChain4j tool loop runs automatically)
 *   │       └── LLM decides: small output → return directly
 *   │                         large output → call submit_batch × N → return summary
 *   ├── 4. Check tool.wasUsed()
 *   │       ├── YES → return tool.getMergedResult()
 *   │       └── NO  → return raw agent output
 *   └── 5. Tool + agent garbage collected (no state leaks)
 * </pre>
 *
 * <h3>Transparent to the framework:</h3>
 * <ul>
 *   <li>If output is small → wrapper is a no-op (zero overhead)</li>
 *   <li>If output is large → wrapper merges batches automatically</li>
 *   <li>Next agent in sequence always receives correct merged output</li>
 * </ul>
 *
 * <h3>Usage in a sequence:</h3>
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
    private final AgentSpec agentSpec;
    private final ObjectMapper objectMapper;

    /**
     * @param agentFactory factory to create fresh agent instances
     * @param agentSpec    the spec for the agent to wrap (includes prompt, guardrails, etc.)
     * @param objectMapper shared Jackson mapper for batch merging
     */
    public BatchingAgentWrapper(AgentFactory agentFactory,
                                AgentSpec agentSpec,
                                ObjectMapper objectMapper) {
        this.agentFactory = agentFactory;
        this.agentSpec = agentSpec;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    //  UntypedAgent API
    // =========================================================================

    @Override
    public Object invoke(Map<String, Object> input) {
        String agentName = agentSpec.name();
        log.debug("[{}] BatchingAgentWrapper — starting invocation", agentName);

        // ── Step 1: Fresh tool for this invocation ──────────────────────
        BatchAccumulatorTool tool = new BatchAccumulatorTool(objectMapper);

        // ── Step 2: Create fresh agent with this tool ───────────────────
        // AgentSpec carries: prompt, guardrails, inputs/outputs config
        // Tool is the only thing that varies per invocation
        UntypedAgent agent = agentFactory.create(agentSpec, tool);

        // ── Step 3: Invoke the agent ────────────────────────────────────
        // LangChain4j's tool execution loop handles everything:
        //   LLM response → tool_call detected → execute tool → send result to LLM → repeat
        //   Until LLM returns a final text response (no more tool_calls)
        Object rawResult = agent.invoke(input);

        // ── Step 4: Check if batching happened ──────────────────────────
        if (tool.wasUsed()) {
            // LLM used batching — rawResult is just a summary string
            // Real data is in the tool's accumulator
            String mergedResult = tool.getMergedResult();

            log.info("[{}] Batching used — {} batches, {} total records merged",
                    agentName, tool.getBatchCount(), tool.getTotalRecordCount());

            return mergedResult;
        }

        // LLM returned everything in one shot — no batching needed
        log.debug("[{}] No batching needed — output returned directly", agentName);
        return rawResult;
    }

    @Override
    public ResultWithAgenticScope<String> invokeWithAgenticScope(Map<String, Object> input) {
        String agentName = agentSpec.name();
        log.debug("[{}] BatchingAgentWrapper — starting invocation (with scope)", agentName);

        // ── Step 1: Fresh tool ──────────────────────────────────────────
        BatchAccumulatorTool tool = new BatchAccumulatorTool(objectMapper);

        // ── Step 2: Fresh agent ─────────────────────────────────────────
        UntypedAgent agent = agentFactory.create(agentSpec, tool);

        // ── Step 3: Invoke with scope access ────────────────────────────
        ResultWithAgenticScope<String> result = agent.invokeWithAgenticScope(input);

        // ── Step 4: Merge if batching happened ──────────────────────────
        if (tool.wasUsed()) {
            String mergedResult = tool.getMergedResult();

            log.info("[{}] Batching used — {} batches, {} total records merged (with scope)",
                    agentName, tool.getBatchCount(), tool.getTotalRecordCount());

            // Return merged result with the original scope
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
        // Scope is per-invocation, not cached — return null
        return null;
    }

    @Override
    public boolean evictAgenticScope(Object memoryId) {
        return false;
    }

    // =========================================================================
    //  Accessors (for debugging/testing)
    // =========================================================================

    /** The spec this wrapper delegates to. */
    public AgentSpec getAgentSpec() {
        return agentSpec;
    }

    /** The agent name (from spec). */
    public String getAgentName() {
        return agentSpec.name();
    }
}
