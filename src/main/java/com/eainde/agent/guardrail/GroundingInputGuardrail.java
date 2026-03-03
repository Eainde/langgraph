package com.eainde.agent.guardrail;

import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailRequest;
import dev.langchain4j.guardrail.InputGuardrailResult;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

/**
 * GLOBAL Input Guardrail — enforces Critical Rules 1 and 2 from CLT-73802.
 *
 * <h3>Critical Rule 1 — Strictly Grounded:</h3>
 * <p>You MUST answer using ONLY the information contained within the provided documents.</p>
 *
 * <h3>Critical Rule 2 — No External Knowledge:</h3>
 * <p>Do NOT use any of your prior knowledge or information from outside the given context.</p>
 *
 * <h3>Validation:</h3>
 * <ul>
 *   <li>Checks that source document text is present in the input</li>
 *   <li>Checks that document text has meaningful content length</li>
 *   <li>FATAL if no documents provided — blocks LLM call entirely</li>
 * </ul>
 *
 * <h3>Applies to: ALL agents (GLOBAL)</h3>
 */
@Log4j2
@Component
public class GroundingInputGuardrail implements InputGuardrail {

    /** Minimum character threshold for meaningful document content. */
    private static final int MIN_DOCUMENT_LENGTH = 50;

    @Override
    public InputGuardrailResult validate(InputGuardrailRequest request) {
        String userMessage = request.userMessage().singleText();

        // Verify source document content is present
        if (!hasDocumentContent(userMessage)) {
            log.error("Grounding guardrail FATAL: no source document content in input");
            return fatal("Critical Rule 1 violation — no source documents provided. "
                    + "Agents must operate ONLY on information contained within provided documents. "
                    + "Cannot proceed without document content.");
        }

        // Verify document text has meaningful length
        int estimatedDocLength = estimateDocumentContentLength(userMessage);
        if (estimatedDocLength < MIN_DOCUMENT_LENGTH) {
            log.warn("Grounding guardrail FATAL: document content too short ({} chars)",
                    estimatedDocLength);
            return fatal("Critical Rule 1 violation — source document content is too short ("
                    + estimatedDocLength + " chars) to contain meaningful data. "
                    + "Ensure complete document text is provided.");
        }

        log.debug("Grounding guardrail PASS — document content present ({} chars estimated)",
                estimatedDocLength);
        return success();
    }

    private boolean hasDocumentContent(String text) {
        return text.contains("DOCUMENT TEXT")
                || text.contains("Source documents:")
                || text.contains("sourceText")
                || text.contains("--- DOCUMENT TEXT ---");
    }

    /**
     * Estimates the length of actual document content within the user message,
     * excluding prompt template boilerplate.
     */
    private int estimateDocumentContentLength(String text) {
        // Try to find content between document markers
        int startIdx = text.indexOf("--- DOCUMENT TEXT ---");
        int endIdx = text.indexOf("--- END ---");
        if (startIdx >= 0 && endIdx > startIdx) {
            return endIdx - startIdx;
        }
        // Fallback: use full message length minus estimated template overhead
        return Math.max(0, text.length() - 200);
    }
}
