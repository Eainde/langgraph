package com.eainde.agent;

package com.db.clm.kyc.ai.config;

import dev.langchain4j.agentic.AgenticScope;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.BeforeToolExecution;
import dev.langchain4j.agentic.observability.ToolExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * AgentListener decorator that seeds default values into the {@link AgenticScope}
 * when the scope is first created.
 *
 * <p>Solves the "first iteration problem" in critic-validation loops where
 * a downstream agent declares an input key that doesn't exist until a later
 * agent in the loop writes it. By seeding the default when the scope is
 * initialized, the loop variable exists before the first agent runs.</p>
 *
 * <h3>Usage:</h3>
 * <pre>
 * .listener(new ScopeInitializingListener(monitor,
 *         Map.of("extractionReview", "")))
 * </pre>
 *
 * <p>All callbacks are delegated to the wrapped listener (e.g. AgentMonitor).</p>
 */
public class ScopeInitializingListener implements AgentListener {

    private static final Logger log = LoggerFactory.getLogger(ScopeInitializingListener.class);

    private final AgentListener delegate;
    private final Map<String, Object> defaults;

    /**
     * @param delegate  the listener to delegate all callbacks to (e.g. AgentMonitor); may be null
     * @param defaults  key-value pairs to seed into scope on creation;
     *                  only written if the key does NOT already exist in scope
     */
    public ScopeInitializingListener(AgentListener delegate, Map<String, Object> defaults) {
        this.delegate = delegate;
        this.defaults = Map.copyOf(defaults);
    }

    // ─── Scope lifecycle — this is where we seed defaults ───────────────

    @Override
    public void afterAgenticScopeCreated(AgenticScope agenticScope) {
        if (delegate != null) {
            delegate.afterAgenticScopeCreated(agenticScope);
        }

        // Seed defaults — only if not already present
        defaults.forEach((key, defaultValue) -> {
            Object existing = agenticScope.readState(key, null);
            if (existing == null) {
                agenticScope.writeState(key, defaultValue);
                log.debug("Seeded scope variable '{}' with default value", key);
            }
        });
    }

    @Override
    public void beforeAgenticScopeDestroyed(AgenticScope agenticScope) {
        if (delegate != null) {
            delegate.beforeAgenticScopeDestroyed(agenticScope);
        }
    }

    // ─── Agent invocation — pure delegation ─────────────────────────────

    @Override
    public void beforeAgentInvocation(AgentRequest agentRequest) {
        if (delegate != null) {
            delegate.beforeAgentInvocation(agentRequest);
        }
    }

    @Override
    public void afterAgentInvocation(AgentResponse agentResponse) {
        if (delegate != null) {
            delegate.afterAgentInvocation(agentResponse);
        }
    }

    @Override
    public void onAgentInvocationError(AgentInvocationError agentInvocationError) {
        if (delegate != null) {
            delegate.onAgentInvocationError(agentInvocationError);
        }
    }

    // ─── Tool execution — pure delegation ───────────────────────────────

    @Override
    public void beforeToolExecution(BeforeToolExecution beforeToolExecution) {
        if (delegate != null) {
            delegate.beforeToolExecution(beforeToolExecution);
        }
    }

    @Override
    public void afterToolExecution(ToolExecution toolExecution) {
        if (delegate != null) {
            delegate.afterToolExecution(toolExecution);
        }
    }

    // ─── Inheritance ────────────────────────────────────────────────────

    @Override
    public boolean inheritedBySubagents() {
        return delegate != null && delegate.inheritedBySubagents();
    }
}