package com.eainde.agent;


import dev.langchain4j.agentic.AgentListener;
import dev.langchain4j.agentic.AgenticScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * AgentListener decorator that seeds default values into the {@link AgenticScope}
 * after the wrapped agent completes.
 *
 * <p>Solves the "first iteration problem" in critic-validation loops where
 * a downstream agent declares an input key that doesn't exist until a later
 * agent in the loop writes it. By seeding the default after a preceding agent
 * in the sequence, the loop variable exists before the loop starts.</p>
 *
 * <h3>Usage:</h3>
 * <pre>
 * // Seed "extractionReview" with "" after source-validator completes,
 * // before the extraction loop starts.
 * .listener(new ScopeInitializingListener(monitor,
 *         Map.of("extractionReview", "")))
 * </pre>
 *
 * <p>The delegate listener (e.g. {@link dev.langchain4j.agentic.AgentMonitor})
 * is called first for all callbacks, then defaults are written.</p>
 */
public class ScopeInitializingListener implements AgentListener {

    private static final Logger log = LoggerFactory.getLogger(ScopeInitializingListener.class);

    private final AgentListener delegate;
    private final Map<String, Object> defaults;

    /**
     * @param delegate  the listener to delegate all callbacks to (e.g. AgentMonitor)
     * @param defaults  key-value pairs to seed into scope after agent completion;
     *                  only written if the key does NOT already exist in scope
     */
    public ScopeInitializingListener(AgentListener delegate, Map<String, Object> defaults) {
        this.delegate = delegate;
        this.defaults = Map.copyOf(defaults);
    }

    @Override
    public void beforeInvocation(AgenticScope scope) {
        if (delegate != null) {
            delegate.beforeInvocation(scope);
        }
    }

    @Override
    public void afterInvocation(AgenticScope scope) {
        if (delegate != null) {
            delegate.afterInvocation(scope);
        }

        // Seed defaults — only if not already present
        defaults.forEach((key, defaultValue) -> {
            Object existing = scope.readState(key, null);
            if (existing == null) {
                scope.writeState(key, defaultValue);
                log.debug("Seeded scope variable '{}' with default value", key);
            }
        });
    }
}
