package com.eainde.agent.tools;

/**
 * ThreadLocal holder for {@link BatchAccumulatorTool}.
 *
 * <p>Connects three components that execute at different phases of an agent's lifecycle:</p>
 * <ol>
 *   <li>{@code BatchResetInputGuardrail} — creates a fresh tool and stores it here (BEFORE LLM call)</li>
 *   <li>{@code BatchAccumulatorTool} — the tool itself, called by the LLM (DURING LLM call)</li>
 *   <li>{@code BatchMergerOutputGuardrail} — reads the tool from here and merges batches (AFTER LLM call)</li>
 * </ol>
 *
 * <p>ThreadLocal ensures each request thread has its own isolated tool instance.
 * No shared state. No stale data across requests.</p>
 *
 * <h3>Lifecycle per agent invocation:</h3>
 * <pre>
 * InputGuardrail  → BatchToolHolder.set(new BatchAccumulatorTool())
 *                      ↓
 * LLM runs        → LLM calls tool.submitBatch() via BatchToolHolder.get()
 *                      ↓
 * OutputGuardrail → BatchToolHolder.get().wasUsed()? → merge → successWith(merged)
 *                 → BatchToolHolder.clear()
 * </pre>
 */
public final class BatchToolHolder {

    private BatchToolHolder() {} // static utility only

    private static final ThreadLocal<BatchAccumulatorTool> CURRENT = new ThreadLocal<>();

    /**
     * Store a fresh tool for the current agent invocation.
     * Called by {@code BatchResetInputGuardrail}.
     */
    public static void set(BatchAccumulatorTool tool) {
        CURRENT.set(tool);
    }

    /**
     * Get the current tool.
     * Called by the tool itself (to find itself) and by {@code BatchMergerOutputGuardrail}.
     */
    public static BatchAccumulatorTool get() {
        return CURRENT.get();
    }

    /**
     * Clear after use. Prevents ThreadLocal leaks.
     * Called by {@code BatchMergerOutputGuardrail} after merging.
     */
    public static void clear() {
        CURRENT.remove();
    }
}
