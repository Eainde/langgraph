package com.eainde.agent.mapper_reducer_u;

package com.db.clm.kyc.ai.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agentic.ResultWithAgenticScope;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.scope.AgenticScope;
import lombok.extern.log4j.Log4j2;

import java.util.Map;

/**
 * Merges Wave 5 parallel agent outputs into enrichedCandidates.
 * Implements {@link UntypedAgent} so it can be placed in a sequence alongside LLM agents.
 *
 * <p><b>This is a Java-only agent — NO LLM call.</b></p>
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
 * <h3>In a sequence:</h3>
 * <pre>
 * agentFactory.sequence("enrichedCandidates",
 *     dedupLinker, csmClassifier, countryOverride, titleExtractor,
 *     scoringEngine, wave5Merger);  // ← this agent
 * </pre>
 *
 * <p>The framework passes accumulated scope state as the input map.
 * This agent reads classifiedCandidates, countryOverrides, titleExtractions,
 * scoredCandidates from the map, merges them, and returns the result.</p>
 */
@Log4j2
public class Wave5MergerAgent implements UntypedAgent {

    private final ObjectMapper objectMapper;

    public Wave5MergerAgent(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    //  UntypedAgent API
    // =========================================================================

    /**
     * Merges Wave 5 outputs from the input map.
     *
     * @param input scope state containing classifiedCandidates, countryOverrides,
     *              titleExtractions, scoredCandidates
     * @return enrichedCandidates JSON string
     */
    @Override
    public Object invoke(Map<String, Object> input) {
        log.info("Wave 5 merge — combining country overrides, titles, and scores");

        String classifiedJson      = getAsString(input, "classifiedCandidates");
        String countryOverridesJson = getAsString(input, "countryOverrides");
        String titleExtractionsJson = getAsString(input, "titleExtractions");
        String scoredCandidatesJson = getAsString(input, "scoredCandidates");

        try {
            String enrichedJson = mergeOutputs(
                    classifiedJson, countryOverridesJson,
                    titleExtractionsJson, scoredCandidatesJson);
            log.info("Wave 5 merge complete");
            return enrichedJson;

        } catch (Exception e) {
            log.error("Wave 5 merge failed — falling back to scoredCandidates", e);
            return scoredCandidatesJson;
        }
    }

    @Override
    public ResultWithAgenticScope<String> invokeWithAgenticScope(Map<String, Object> input) {
        String result = invoke(input).toString();
        return new ResultWithAgenticScope<>(null, result);
    }

    @Override
    public AgenticScope getAgenticScope(Object memoryId) {
        return null; // No scope management — framework handles this in sequences
    }

    @Override
    public boolean evictAgenticScope(Object memoryId) {
        return false;
    }

    // =========================================================================
    //  Core Merge Logic
    // =========================================================================

    /**
     * Merges four agent outputs by matching candidates on {@code id}.
     *
     * <p>Also usable directly from CsmPipelineOrchestrator for the chunked path.</p>
     */
    public String mergeOutputs(String classifiedJson,
                               String countryOverridesJson,
                               String titleExtractionsJson,
                               String scoredCandidatesJson) throws JsonProcessingException {

        JsonNode classified      = parseJson(classifiedJson);
        JsonNode countryOverrides = parseJson(countryOverridesJson);
        JsonNode titleExtractions = parseJson(titleExtractionsJson);
        JsonNode scoredCandidates = parseJson(scoredCandidatesJson);

        ArrayNode baseCandidates = findCandidateArray(classified,
                "classified_candidates", "candidates");
        ArrayNode countryArray = findCandidateArray(countryOverrides,
                "country_overrides", "candidates");
        ArrayNode titleArray = findCandidateArray(titleExtractions,
                "title_extractions", "candidates");
        ArrayNode scoreArray = findCandidateArray(scoredCandidates,
                "scored_candidates", "candidates");

        ArrayNode enriched = objectMapper.createArrayNode();

        for (int i = 0; i < baseCandidates.size(); i++) {
            ObjectNode candidate = baseCandidates.get(i).deepCopy();
            int candidateId = candidate.has("id") ? candidate.get("id").asInt() : i + 1;

            // Agent 6: Country Override — isCsm OVERRIDE (stricter local rules)
            JsonNode countryRecord = findById(countryArray, candidateId);
            if (countryRecord != null) {
                overlayField(candidate, countryRecord, "isCsm");
                overlayField(candidate, countryRecord, "countryProfileApplied");
                overlayField(candidate, countryRecord, "countryOverrideNote");
            }

            // Agent 7: Title Extractor — ADD titles
            JsonNode titleRecord = findById(titleArray, candidateId);
            if (titleRecord != null) {
                overlayField(candidate, titleRecord, "jobTitle");
                overlayField(candidate, titleRecord, "personalTitle");
                overlayField(candidate, titleRecord, "anchorNote");
            }

            // Agent 8: Scoring Engine — ADD scores
            JsonNode scoreRecord = findById(scoreArray, candidateId);
            if (scoreRecord != null) {
                overlayField(candidate, scoreRecord, "score");
                overlayField(candidate, scoreRecord, "scoreBreakdown");
                overlayField(candidate, scoreRecord, "qualityGateNotes");
            }

            enriched.add(candidate);
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.set("enriched_candidates", enriched);

        log.info("Merged {} base candidates with {} overrides, {} titles, {} scores",
                baseCandidates.size(), countryArray.size(),
                titleArray.size(), scoreArray.size());

        return objectMapper.writeValueAsString(result);
    }

    // =========================================================================
    //  Internal Helpers
    // =========================================================================

    private JsonNode parseJson(String json) throws JsonProcessingException {
        return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
    }

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

    private JsonNode findById(ArrayNode array, int id) {
        for (JsonNode node : array) {
            if (node.has("id") && node.get("id").asInt() == id) {
                return node;
            }
        }
        return null;
    }

    private void overlayField(ObjectNode target, JsonNode source, String fieldName) {
        if (source.has(fieldName) && !source.get(fieldName).isNull()) {
            target.set(fieldName, source.get(fieldName).deepCopy());
        }
    }

    private String getAsString(Map<String, Object> input, String key) {
        Object value = input.get(key);
        return value != null ? value.toString() : "";
    }
}