package com.eainde.agent.tools;

import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailRequest;
import dev.langchain4j.guardrail.InputGuardrailResult;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

/**
 * Input Guardrail — resets the {@link BatchAccumulatorTool} before each agent runs.
 *
 * <p>MUST be the FIRST input guardrail in every agent's chain. This ensures
 * that each agent starts with a clean tool — no stale batches from a previous
 * agent in the sequence or a previous request.</p>
 *
 * <h3>Applies to: ALL agents that have BatchAccumulatorTool</h3>
 * <h3>Outcome: Always SUCCESS (pass-through)</h3>
 */
@Log4j2
@Component
public class BatchResetInputGuardrail implements InputGuardrail {

    private final BatchAccumulatorTool tool;

    public BatchResetInputGuardrail(BatchAccumulatorTool tool) {
        this.tool = tool;
    }

    @Override
    public InputGuardrailResult validate(InputGuardrailRequest request) {
        tool.reset();
        log.debug("Batch accumulator reset for next agent invocation");
        return success();
    }
}
