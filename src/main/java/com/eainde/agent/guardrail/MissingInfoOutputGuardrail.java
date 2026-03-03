package com.eainde.agent.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * GLOBAL Output Guardrail — enforces Critical Rule 4 from CLT-73802.
 *
 * <h3>Critical Rule 4 — Handle Missing Information:</h3>
 * <p>"If an answer cannot be found in the documents, you must state that the
 * information is not available. Do not guess or infer."</p>
 *
 * <h3>Validation:</h3>
 * <ul>
 *   <li>Detects placeholder/fabricated values that suggest guessing:
 *       "N/A", "Unknown", "Not specified", "TBD", "TBC", "Assumed"</li>
 *   <li>Fields that SHOULD be null when not evidenced must not contain
 *       fabricated values — they should be explicitly {@code null}</li>
 *   <li>Nullable fields: middleName, personalTitle, jobTitle, formerEffectiveDate</li>
 *   <li>Uses REWRITE to replace placeholder strings with {@code null}</li>
 * </ul>
 *
 * <h3>Applies to: Agents 3, 5, 7, 9, 10</h3>
 * <h3>Outcome: REWRITE — replaces guessed placeholder values with null</h3>
 */
@Log4j2
@Component
public class MissingInfoOutputGuardrail implements OutputGuardrail {

    private final ObjectMapper objectMapper;

    /** Placeholder strings that indicate guessing/inference rather than evidence. */
    private static final List<String> PLACEHOLDER_VALUES = List.of(
            "n/a", "na", "not available", "not specified", "unknown",
            "not provided", "not found", "none", "tbd", "tbc",
            "assumed", "inferred", "likely", "probably", "possibly",
            "not evidenced", "not stated", "unspecified", "-"
    );

    /** Fields that should be null (not placeholder) when information is not in source. */
    private static final List<String> NULLABLE_FIELDS = List.of(
            "middleName", "personalTitle", "jobTitle",
            "formerEffectiveDate", "countryOverrideNote", "anchorNote",
            "normalizationNote", "dedupNote"
    );

    public MissingInfoOutputGuardrail(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public OutputGuardrailResult validate(OutputGuardrailRequest request) {
        String output = request.responseFromLLM().text();

        if (output == null || output.isBlank()) {
            return success(); // Empty output handled by other guardrails
        }

        try {
            JsonNode root = objectMapper.readTree(output);
            List<String> fixes = new ArrayList<>();
            boolean modified = false;

            // Find and fix placeholder values in candidate arrays
            for (String arrayKey : List.of(
                    "raw_names", "normalized_candidates", "deduped_candidates",
                    "classified_candidates", "enriched_candidates",
                    "reasoned_candidates", "extracted_records",
                    "title_extractions", "country_overrides", "scored_candidates")) {

                if (root.has(arrayKey) && root.get(arrayKey).isArray()) {
                    for (JsonNode candidate : root.get(arrayKey)) {
                        if (candidate.isObject()) {
                            modified |= fixPlaceholders((ObjectNode) candidate, fixes);
                        }
                    }
                }
            }

            if (modified) {
                String fixedOutput = objectMapper.writeValueAsString(root);
                log.info("Missing info guardrail: replaced {} placeholder values with null — {}",
                        fixes.size(), String.join("; ", fixes));
                return successWith(fixedOutput);
            }

            log.debug("Missing info guardrail PASS — no placeholder values detected");
            return success();

        } catch (Exception e) {
            log.debug("Missing info guardrail: skipping — output not parseable");
            return success();
        }
    }

    /**
     * Checks nullable fields for placeholder values and replaces them with null.
     *
     * @return true if any field was modified
     */
    private boolean fixPlaceholders(ObjectNode candidate, List<String> fixes) {
        boolean modified = false;
        int id = candidate.has("id") ? candidate.get("id").asInt() : -1;

        for (String field : NULLABLE_FIELDS) {
            if (candidate.has(field) && !candidate.get(field).isNull()) {
                String value = candidate.get(field).asText().trim().toLowerCase();

                if (isPlaceholder(value)) {
                    candidate.putNull(field);
                    modified = true;
                    fixes.add("Candidate " + id + "." + field
                            + " was '" + candidate.get(field) + "' → null");
                }
            }
        }

        return modified;
    }

    /**
     * Checks if a value is a placeholder indicating guessing/inference.
     */
    private boolean isPlaceholder(String value) {
        if (value.isEmpty()) return true; // Empty string should be null per J3

        for (String placeholder : PLACEHOLDER_VALUES) {
            if (value.equals(placeholder)) return true;
        }

        return false;
    }
}
