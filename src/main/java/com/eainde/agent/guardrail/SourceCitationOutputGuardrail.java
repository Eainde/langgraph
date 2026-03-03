package com.eainde.agent.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * GLOBAL Output Guardrail — enforces Critical Rule 3 from CLT-73802.
 *
 * <h3>Critical Rule 3 — Cite Your Sources:</h3>
 * <p>"For every answer found, you MUST provide the source document's name
 * and the specific page number."</p>
 *
 * <h3>Validation:</h3>
 * <ul>
 *   <li>Every candidate/record in the output must have a non-null {@code documentName}</li>
 *   <li>Every candidate/record must have a valid {@code pageNumber} (&gt; 0)</li>
 *   <li>Uses REPROMPT to ask the LLM to fix missing citations</li>
 * </ul>
 *
 * <h3>Applies to: Agents 1, 3, 4, 5, 9, 10</h3>
 * <h3>Outcome: REPROMPT if citations missing (triggers LLM retry with feedback)</h3>
 */
@Log4j2
@Component
public class SourceCitationOutputGuardrail implements OutputGuardrail {

    private final ObjectMapper objectMapper;

    public SourceCitationOutputGuardrail(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public OutputGuardrailResult validate(OutputGuardrailRequest request) {
        String output = request.responseFromLLM().text();

        if (output == null || output.isBlank()) {
            return reprompt("Output is empty",
                    "You returned an empty response. Provide a valid JSON response "
                            + "with documentName and pageNumber for every candidate.");
        }

        try {
            JsonNode root = objectMapper.readTree(output);
            List<String> violations = new ArrayList<>();

            // Find candidate arrays across different agent output formats
            checkCandidateArray(root, violations,
                    "raw_names", "normalized_candidates", "deduped_candidates",
                    "classified_candidates", "enriched_candidates",
                    "reasoned_candidates", "extracted_records", "candidates");

            if (!violations.isEmpty()) {
                String violationSummary = String.join("; ", violations);
                log.warn("Citation guardrail: {} violations found — {}", violations.size(),
                        violationSummary);

                return reprompt(
                        "Critical Rule 3 violation — missing source citations: " + violationSummary,
                        "Your response violates Critical Rule 3: 'For every answer found, "
                                + "you MUST provide the source document's name and the specific "
                                + "page number.' Fix these issues:\n" + violationSummary
                                + "\nEnsure every candidate has a non-null documentName "
                                + "and pageNumber > 0.");
            }

            log.debug("Citation guardrail PASS — all candidates have citations");
            return success();

        } catch (Exception e) {
            // If we can't parse JSON, let the JsonSchema guardrail handle it
            log.debug("Citation guardrail: skipping — output is not parseable JSON");
            return success();
        }
    }

    private void checkCandidateArray(JsonNode root, List<String> violations, String... keys) {
        for (String key : keys) {
            if (root.has(key) && root.get(key).isArray()) {
                for (JsonNode candidate : root.get(key)) {
                    int id = candidate.has("id") ? candidate.get("id").asInt() : -1;
                    String label = id > 0 ? "Candidate " + id : "A candidate";

                    // documentName must be present and non-null
                    if (!candidate.has("documentName")
                            || candidate.get("documentName").isNull()
                            || candidate.get("documentName").asText().isBlank()) {
                        violations.add(label + " is missing documentName");
                    }

                    // pageNumber must be present and > 0
                    if (!candidate.has("pageNumber")
                            || candidate.get("pageNumber").isNull()
                            || candidate.get("pageNumber").asInt() <= 0) {
                        violations.add(label + " has invalid or missing pageNumber");
                    }
                }
                return; // Found and checked the array
            }
        }
    }
}
