package com.deutschebank.nexusai.agent.nonai;

import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public abstract class AbstractNonAiUntypedAgent implements UntypedAgent {

    // AgenticScopeAccess: framework calls these to manage scope lifecycle.
    // Non-AI agents hold no LLM memory — so we keep a minimal map
    // keyed by memoryId, storing only the scope reference the framework
    // passed us via invokeWithAgenticScope.
    private final ConcurrentHashMap<Object, AgenticScope> scopeMap = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // UntypedAgent
    // -------------------------------------------------------------------------

    @Override
    public Object invoke(Map<String, Object> input) {
        // Scope is not available via invoke() alone — use invokeWithAgenticScope
        // if you need scope access. For simple pass-through agents this is fine.
        return process(input, null);
    }

    @Override
    public ResultWithAgenticScope<String> invokeWithAgenticScope(Map<String, Object> input) {
        // The framework calls this when it needs both the result AND the scope.
        // We look up the scope from the map (populated by the framework-owned
        // sequence runner setting it before calling us).
        Object memoryId = extractMemoryId(input);
        AgenticScope scope = scopeMap.get(memoryId);

        Object result = process(input, scope);
        String resultStr = result != null ? result.toString() : null;

        return ResultWithAgenticScope.of(resultStr, scope);
    }

    // -------------------------------------------------------------------------
    // AgenticScopeAccess
    // -------------------------------------------------------------------------

    @Override
    public AgenticScope getAgenticScope(Object memoryId) {
        return scopeMap.get(memoryId);
    }

    @Override
    public boolean evictAgenticScope(Object memoryId) {
        return scopeMap.remove(memoryId) != null;
    }

    // Package-visible so the sequence runner / framework can register
    // the scope it created before invoking this agent
    public void registerScope(Object memoryId, AgenticScope scope) {
        if (scope != null) {
            scopeMap.put(memoryId, scope);
        }
    }

    // -------------------------------------------------------------------------
    // Subclass contract
    // -------------------------------------------------------------------------

    /**
     * Implement deterministic logic here. No LLM.
     *
     * @param input  Map from the previous agent in the sequence
     * @param scope  Shared AgenticScope — may be null if framework didn't
     *               populate it yet. Always null-check.
     */
    protected abstract Object process(Map<String, Object> input, AgenticScope scope);

    protected Object extractMemoryId(Map<String, Object> input) {
        if (input.containsKey("memoryId")) return input.get("memoryId");
        if (input.containsKey("traceId"))  return input.get("traceId");
        return "default";
    }
}