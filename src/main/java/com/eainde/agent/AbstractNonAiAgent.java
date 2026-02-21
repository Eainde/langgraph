package com.deutschebank.nexusai.agent.nonai;

import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.scope.AgenticScopeRegistry;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Base class for all non-AI deterministic agents.
 *
 * Core insight from DefaultAgenticScope source:
 *  - We CANNOT instantiate DefaultAgenticScope (package-private constructors)
 *  - The framework creates and owns the scope via AgenticScopeRegistry
 *  - We inject AgenticScopeRegistry to LOOK UP the scope already created
 *    by the sequence runner for the current memoryId
 *  - getAgenticScope() / evictAgenticScope() delegate to the registry
 */
@Slf4j
public abstract class AbstractNonAiAgent implements UntypedAgent {

    /**
     * The framework registry that holds ALL live AgenticScope instances.
     * The sequence runner creates the scope before invoking any agent —
     * so by the time our non-AI agent runs, the scope already exists here.
     */
    private final AgenticScopeRegistry agenticScopeRegistry;

    protected AbstractNonAiAgent(AgenticScopeRegistry agenticScopeRegistry) {
        this.agenticScopeRegistry = agenticScopeRegistry;
    }

    // -------------------------------------------------------------------------
    // UntypedAgent — entry points called by sequenceBuilder
    // -------------------------------------------------------------------------

    @Override
    public Object invoke(Map<String, Object> input) {
        Object memoryId = extractMemoryId(input);
        AgenticScope scope = agenticScopeRegistry.get(memoryId);   // look up, not create

        log.debug("[{}] {} invoked. scope found={}",
                memoryId, agentName(), scope != null);

        return process(input, scope);
    }

    @Override
    public ResultWithAgenticScope<String> invokeWithAgenticScope(Map<String, Object> input) {
        Object memoryId = extractMemoryId(input);
        AgenticScope scope = agenticScopeRegistry.get(memoryId);   // same lookup

        Object result = process(input, scope);
        String resultStr = result != null ? result.toString() : null;

        // Hand back the framework-owned scope — we never construct one ourselves
        return new ResultWithAgenticScope( scope,resultStr);
    }

    // -------------------------------------------------------------------------
    // AgenticScopeAccess — delegate entirely to the registry
    // -------------------------------------------------------------------------

    @Override
    public AgenticScope getAgenticScope(Object memoryId) {
        // Non-AI agents hold no LLM memory of their own.
        // Delegate to the registry — the framework created the scope there.
        return agenticScopeRegistry.get(memoryId);
    }

    @Override
    public boolean evictAgenticScope(Object memoryId) {
        // Non-AI agents should not evict the shared scope —
        // that would destroy state for all downstream agents.
        // Return false: nothing to evict on our behalf.
        return false;
    }

    // -------------------------------------------------------------------------
    // Subclass contract — implement your deterministic logic here
    // -------------------------------------------------------------------------

    /**
     * @param input  Input map from the previous step in the sequence
     * @param scope  The shared AgenticScope created by the framework for this
     *               sequence run. May be null if the registry hasn't initialised
     *               it yet — always null-check before reading.
     *               Use scope.writeState() / scope.readState() to share state
     *               with AI agents in the same sequence.
     * @return       Result forwarded to the next step
     */
    protected abstract Object process(Map<String, Object> input, AgenticScope scope);

    protected String agentName() {
        return getClass().getSimpleName();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    protected Object extractMemoryId(Map<String, Object> input) {
        if (input.containsKey("memoryId")) return input.get("memoryId");
        if (input.containsKey("traceId"))  return input.get("traceId");
        return "default";
    }
}