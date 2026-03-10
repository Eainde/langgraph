package com.eainde.agent.tools;

import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

/**
 * Output Guardrail — replaces the LLM's summary text with actual merged batch data.
 *
 * <p>When the LLM uses the {@code submit_batch} tool, its final response is a summary
 * string like "Extraction complete. 130 records submitted." The actual data is
 * in the {@link BatchAccumulatorTool}. This guardrail replaces the summary with
 * the merged batch data so the next agent in the sequence receives correct JSON.</p>
 *
 * <p>MUST be the LAST output guardrail in every agent's chain, so other guardrails
 * (like JsonSchema, Citation) don't validate the summary string.</p>
 *
 * <h3>Flow:</h3>
 * <pre>
 * Agent output: "Extraction complete. 130 records submitted."
 *       ↓
 * BatchMergerOutputGuardrail:
 *   tool.wasUsed() == true
 *   → merged = tool.getMergedResult()  // {"raw_names": [130 records]}
 *   → return successWith(merged)       // framework replaces output
 *       ↓
 * Scope receives: {"raw_names": [130 records]}  ← correct data for next agent
 * </pre>
 *
 * <h3>When tool was NOT used (small output):</h3>
 * <pre>
 * Agent output: {"raw_names": [30 records]}   // returned directly by LLM
 *       ↓
 * BatchMergerOutputGuardrail:
 *   tool.wasUsed() == false
 *   → return success()                // pass-through, no changes
 * </pre>
 *
 * <h3>Applies to: ALL agents that have BatchAccumulatorTool</h3>
 * <h3>Outcome: REWRITE if batching happened, SUCCESS otherwise</h3>
 */
@Log4j2
@Component
public class BatchMergerOutputGuardrail implements OutputGuardrail {

    private final BatchAccumulatorTool tool;

    public BatchMergerOutputGuardrail(BatchAccumulatorTool tool) {
        this.tool = tool;
    }

    @Override
    public OutputGuardrailResult validate(OutputGuardrailRequest request) {
        if (!tool.wasUsed()) {
            log.debug("Batch merger: tool not used — passing through");
            return success();
        }

        // Tool was used — LLM's response is just a summary, real data is in the tool
        String mergedResult = tool.getMergedResult();

        log.info("Batch merger: replacing LLM summary with merged data — "
                        + "{} batches, {} total records",
                tool.getBatchCount(), tool.getTotalRecordCount());

        // Reset after reading (clean for next agent in sequence)
        tool.reset();

        // Replace the LLM's summary with actual merged JSON
        return successWith(mergedResult);
    }
}