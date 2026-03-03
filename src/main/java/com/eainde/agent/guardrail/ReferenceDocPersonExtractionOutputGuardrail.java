package com.eainde.agent.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Agent-specific Output Guardrail — validates that NO persons were extracted
 * from reference/exemplar documents.
 *
 * <h3>From CLT-73802:</h3>
 * <pre>
 * "You must not, under any circumstances, extract any natural person
 *  or non-natural person mentioned within these documents."
 *
 * Reference documents:
 * • Client_Senior_Managers_KOS_v8.0.pdf
 * • ID_and_V_Matrix_KOS_v8.0.tsv
 * • Any document in KOS/8.0/ folder path
 * </pre>
 *
 * <h3>Validation:</h3>
 * <ul>
 *   <li>Scans every candidate's {@code documentName} field</li>
 *   <li>If any candidate cites a reference document as its source,
 *       the guardrail triggers REPROMPT to remove those candidates</li>
 *   <li>Applies Name Matching Rules 1-7 to detect reference doc references</li>
 * </ul>
 *
 * <h3>Applies to: Agent 1 (Candidate Extractor)</h3>
 * <h3>Outcome: REPROMPT — tells agent to remove candidates from reference docs</h3>
 */
@Log4j2
@Component
public class ReferenceDocPersonExtractionOutputGuardrail implements OutputGuardrail {

    private final ObjectMapper objectMapper;

    /** Exact reference document file names. */
    private static final List<String> REFERENCE_DOC_NAMES = List.of(
            "Client_Senior_Managers_KOS_v8.0.pdf",
            "Client_Senior_Managers_KOS_v8.0",
            "ID_and_V_Matrix_KOS_v8.0.tsv",
            "ID_and_V_Matrix_KOS_v8.0"
    );

    /**
     * Patterns for reference doc detection using Name Matching Rules.
     *
     * <pre>
     * Rule 2: Folder path — KOS/8.0/anything
     * Rule 3: Case-insensitive exact match
     * Rule 5: Abbreviated — CSM_KOS, IDV_Matrix
     * Rule 6: Keyword proximity — "Client" + "Senior" + "Managers" + "KOS" nearby
     * </pre>
     */
    private static final List<Pattern> REFERENCE_DOC_PATTERNS = List.of(
            Pattern.compile("(?i)KOS[/\\\\]8\\.0[/\\\\]"),
            Pattern.compile("(?i)Client[_\\s]Senior[_\\s]Managers[_\\s]KOS"),
            Pattern.compile("(?i)ID[_\\s]and[_\\s]V[_\\s]Matrix[_\\s]KOS"),
            Pattern.compile("(?i)CSM[_\\s]KOS[_\\s]?v?8\\.?0"),
            Pattern.compile("(?i)IDV[_\\s]?Matrix[_\\s]?v?8\\.?0")
    );

    public ReferenceDocPersonExtractionOutputGuardrail(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public OutputGuardrailResult validate(OutputGuardrailRequest request) {
        String output = request.responseFromLLM().text();

        if (output == null || output.isBlank()) {
            return success();
        }

        try {
            JsonNode root = objectMapper.readTree(output);
            List<String> violations = new ArrayList<>();

            // Check all candidate array keys
            for (String arrayKey : List.of(
                    "raw_names", "normalized_candidates", "candidates",
                    "extracted_records")) {

                if (root.has(arrayKey) && root.get(arrayKey).isArray()) {
                    checkCandidatesForReferenceDocs(
                            (ArrayNode) root.get(arrayKey), violations);
                }
            }

            // Also check entities_found
            if (root.has("entities_found") && root.get("entities_found").isArray()) {
                checkCandidatesForReferenceDocs(
                        (ArrayNode) root.get("entities_found"), violations);
            }

            if (!violations.isEmpty()) {
                String violationList = String.join("\n• ", violations);
                log.warn("Reference doc extraction guardrail: {} violations — {}",
                        violations.size(), violationList);

                return reprompt(
                        "CRITICAL: Persons extracted from reference documents: " + violationList,
                        "CRITICAL VIOLATION: You extracted persons from CSM reference/exemplar "
                                + "documents. This is explicitly forbidden.\n\n"
                                + "Remove ALL of the following candidates from your output — "
                                + "they come from reference documents that define CSM criteria, "
                                + "NOT from client source documents:\n• " + violationList
                                + "\n\nRe-read the source documents and extract persons ONLY from "
                                + "actual client documents (registry extracts, AoA, filings, etc). "
                                + "Do NOT extract from Client_Senior_Managers_KOS_v8.0.pdf or "
                                + "ID_and_V_Matrix_KOS_v8.0.tsv.");
            }

            log.debug("Reference doc extraction guardrail PASS — no reference doc extractions");
            return success();

        } catch (Exception e) {
            log.debug("Reference doc extraction guardrail: skipping — output not parseable");
            return success();
        }
    }

    private void checkCandidatesForReferenceDocs(ArrayNode candidates,
                                                 List<String> violations) {
        for (JsonNode candidate : candidates) {
            if (!candidate.has("documentName") || candidate.get("documentName").isNull()) {
                continue;
            }

            String docName = candidate.get("documentName").asText();
            int id = candidate.has("id") ? candidate.get("id").asInt() : -1;
            String name = candidate.has("nameAsSource")
                    ? candidate.get("nameAsSource").asText()
                    : candidate.has("firstName")
                    ? candidate.get("firstName").asText() + " "
                    + (candidate.has("lastName") ? candidate.get("lastName").asText() : "")
                    : "id=" + id;

            if (isReferenceDocument(docName)) {
                violations.add("Candidate " + id + " ('" + name
                        + "') extracted from reference doc '" + docName + "'");
            }
        }
    }

    private boolean isReferenceDocument(String documentName) {
        if (documentName == null || documentName.isBlank()) return false;

        // Check exact names
        for (String refDoc : REFERENCE_DOC_NAMES) {
            if (documentName.equalsIgnoreCase(refDoc)) return true;
        }

        // Check patterns
        for (Pattern pattern : REFERENCE_DOC_PATTERNS) {
            if (pattern.matcher(documentName).find()) return true;
        }

        return false;
    }
}
