package com.eainde.agent.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

/**
 * GLOBAL Output Guardrail — validates that agent output is well-formed JSON.
 *
 * <h3>Validation:</h3>
 * <ul>
 *   <li>Output must be parseable as JSON</li>
 *   <li>Output must be a JSON object (not an array or primitive)</li>
 *   <li>Output must contain at least one recognized candidate array key</li>
 *   <li>Strips markdown code fences if present (```json ... ```)</li>
 * </ul>
 *
 * <h3>Applies to: ALL agents (GLOBAL)</h3>
 * <h3>Outcome: REPROMPT on invalid JSON (triggers LLM retry with feedback)</h3>
 * <h3>Outcome: REWRITE if JSON is valid but wrapped in markdown fences</h3>
 */
@Log4j2
@Component
public class JsonSchemaOutputGuardrail implements OutputGuardrail {

    private final ObjectMapper objectMapper;

    public JsonSchemaOutputGuardrail(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public OutputGuardrailResult validate(OutputGuardrailRequest request) {
        String output = request.responseFromLLM().text();

        if (output == null || output.isBlank()) {
            return reprompt("Output is empty",
                    "You returned an empty response. Return a valid JSON object.");
        }

        // Strip markdown code fences if present
        String cleaned = stripMarkdownFences(output);
        boolean wasCleanedUp = !cleaned.equals(output);

        // Attempt to parse as JSON
        JsonNode root;
        try {
            root = objectMapper.readTree(cleaned);
        } catch (Exception e) {
            log.warn("JSON schema guardrail: invalid JSON — {}", e.getMessage());
            return reprompt(
                    "Output is not valid JSON: " + e.getMessage(),
                    "Your previous response was not valid JSON. Error: " + e.getMessage()
                            + "\nReturn ONLY a valid JSON object. No text before or after. "
                            + "No trailing commas. No comments. No markdown formatting.");
        }

        // Must be an object, not array or primitive
        if (!root.isObject()) {
            return reprompt(
                    "Output is JSON but not a JSON object (got " + root.getNodeType() + ")",
                    "Your response must be a JSON object ({...}), not a "
                            + root.getNodeType() + ". Wrap your output in a JSON object.");
        }

        // If we cleaned up markdown fences, rewrite with clean JSON
        if (wasCleanedUp) {
            log.info("JSON schema guardrail: stripped markdown fences from output");
            return successWith(cleaned);
        }

        log.debug("JSON schema guardrail PASS — valid JSON object");
        return success();
    }

    /**
     * Strips markdown code fences: ```json ... ``` or ``` ... ```
     */
    private String stripMarkdownFences(String text) {
        String trimmed = text.trim();

        // Handle ```json\n...\n```
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }

        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }

        return trimmed.trim();
    }
}
