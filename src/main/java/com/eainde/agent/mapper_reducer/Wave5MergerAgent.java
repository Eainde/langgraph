package com.eainde.agent.mapper_reducer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.scope.AgenticScope;
import lombok.extern.log4j.Log4j2;

/**
 * Merges Wave 5 parallel agent outputs into a single enrichedCandidates JSON.
 * This is a Java-only agent — NO LLM call.
 *
 * <h3>Merge logic:</h3>
 * <pre>
 * classifiedCandidates (base from Agent 5)
 *   ├── Agent 6 overlays: isCsm, countryProfileApplied, countryOverrideNote
 *   ├── Agent 7 overlays: jobTitle, personalTitle, anchorNote
 *   └── Agent 8 overlays: score, scoreBreakdown, qualityGateNotes
 *   = enrichedCandidates
 * </pre>
 *
 * <h3>Merge rules:</h3>
 * <ol>
 *   <li>Base record = classifiedCandidates[i] (from Agent 5)</li>
 *   <li>Match by candidate {@code id} field across all outputs</li>
 *   <li>Agent 6 {@code isCsm} OVERRIDES Agent 5 (country profile is stricter)</li>
 *   <li>Agent 7 titles are ADDED (were null from Agent 5)</li>
 *   <li>Agent 8 scores are ADDED (new fields)</li>
 *   <li>If an agent didn't produce output for a candidate, base values kept</li>
 *   <li>If merge fails entirely, fall back to scoredCandidates</li>
 * </ol>
 *
 * <h3>Reads from scope:</h3>
 * <ul>
 *   <li>{@code classifiedCandidates} — base (Agent 5)</li>
 *   <li>{@code countryOverrides} — Agent 6 output</li>
 *   <li>{@code titleExtractions} — Agent 7 output</li>
 *   <li>{@code scoredCandidates} — Agent 8 output</li>
 * </ul>
 *
 * <h3>Writes to scope:</h3>
 * <ul>
 *   <li>{@code enrichedCandidates} — merged result</li>
 * </ul>
 */
@Log4j2
public class Wave5MergerAgent implements UntypedAgent {

    private final ObjectMapper objectMapper;

    public Wave5MergerAgent(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void invoke(AgenticScope scope) {
        log.info("Wave 5 merge — combining country overrides, titles, and scores");

        String classifiedJson      = readString(scope, "classifiedCandidates");
        String countryOverridesJson = readString(scope, "countryOverrides");
        String titleExtractionsJson = readString(scope, "titleExtractions");
        String scoredCandidatesJson = readString(scope, "scoredCandidates");

        try {
            String enrichedJson = mergeOutputs(
                    classifiedJson, countryOverridesJson,
                    titleExtractionsJson, scoredCandidatesJson);

            scope.writeState("enrichedCandidates", enrichedJson);
            log.info("Wave 5 merge complete — enrichedCandidates written to scope");

        } catch (Exception e) {
            log.error("Wave 5 merge failed — falling back to scoredCandidates", e);
            scope.writeState("enrichedCandidates", scoredCandidatesJson);
        }
    }

    /**
     * Core merge logic — match by id, overlay fields.
     */
    String mergeOutputs(String classifiedJson,
                        String countryOverridesJson,
                        String titleExtractionsJson,
                        String scoredCandidatesJson) throws JsonProcessingException {

        JsonNode classified = objectMapper.readTree(
                classifiedJson.isBlank() ? "{}" : classifiedJson);
        JsonNode countryOverrides = objectMapper.readTree(
                countryOverridesJson.isBlank() ? "{}" : countryOverridesJson);
        JsonNode titleExtractions = objectMapper.readTree(
                titleExtractionsJson.isBlank() ? "{}" : titleExtractionsJson);
        JsonNode scoredCandidates = objectMapper.readTree(
                scoredCandidatesJson.isBlank() ? "{}" : scoredCandidatesJson);

        // Get candidate arrays — try common key names
        ArrayNode baseCandidates = findCandidateArray(classified,
                "classified_candidates", "candidates");
        ArrayNode countryArray = findCandidateArray(countryOverrides,
                "country_overrides", "candidates");
        ArrayNode titleArray = findCandidateArray(titleExtractions,
                "title_extractions", "candidates");
        ArrayNode scoreArray = findCandidateArray(scoredCandidates,
                "scored_candidates", "candidates");

        // Merge
        ArrayNode enriched = objectMapper.createArrayNode();

        for (int i = 0; i < baseCandidates.size(); i++) {
            ObjectNode candidate = baseCandidates.get(i).deepCopy();
            int candidateId = candidate.has("id") ? candidate.get("id").asInt() : i + 1;

            // Agent 6: Country Override fields
            JsonNode countryRecord = findById(countryArray, candidateId);
            if (countryRecord != null) {
                overlayField(candidate, countryRecord, "isCsm");
                overlayField(candidate, countryRecord, "countryProfileApplied");
                overlayField(candidate, countryRecord, "countryOverrideNote");
            }

            // Agent 7: Title Extractor fields
            JsonNode titleRecord = findById(titleArray, candidateId);
            if (titleRecord != null) {
                overlayField(candidate, titleRecord, "jobTitle");
                overlayField(candidate, titleRecord, "personalTitle");
                overlayField(candidate, titleRecord, "anchorNote");
            }

            // Agent 8: Scoring Engine fields
            JsonNode scoreRecord = findById(scoreArray, candidateId);
            if (scoreRecord != null) {
                overlayField(candidate, scoreRecord, "score");
                overlayField(candidate, scoreRecord, "scoreBreakdown");
                overlayField(candidate, scoreRecord, "qualityGateNotes");
            }

            enriched.add(candidate);
        }

        // Wrap in result object
        ObjectNode result = objectMapper.createObjectNode();
        result.set("enriched_candidates", enriched);

        log.info("Merged {} base candidates with {} country overrides, {} title extractions, {} scores",
                baseCandidates.size(), countryArray.size(),
                titleArray.size(), scoreArray.size());

        return objectMapper.writeValueAsString(result);
    }

    // =========================================================================
    //  Internal Helpers
    // =========================================================================

    /**
     * Finds a candidate array inside a JSON response.
     * Tries multiple common key names since different agents use different keys.
     */
    private ArrayNode findCandidateArray(JsonNode root, String... possibleKeys) {
        for (String key : possibleKeys) {
            if (root.has(key) && root.get(key).isArray()) {
                return (ArrayNode) root.get(key);
            }
        }
        if (root.isArray()) {
            return (ArrayNode) root;
        }
        return objectMapper.createArrayNode();
    }

    /**
     * Finds a record by id within an array.
     */
    private JsonNode findById(ArrayNode array, int id) {
        for (JsonNode node : array) {
            if (node.has("id") && node.get("id").asInt() == id) {
                return node;
            }
        }
        return null;
    }

    /**
     * Overlays a single field from source to target if it exists and is not null.
     */
    private void overlayField(ObjectNode target, JsonNode source, String fieldName) {
        if (source.has(fieldName) && !source.get(fieldName).isNull()) {
            target.set(fieldName, source.get(fieldName).deepCopy());
        }
    }

    private String readString(AgenticScope scope, String key) {
        Object value = scope.readState(key);
        return value != null ? value.toString() : "";
    }
}
