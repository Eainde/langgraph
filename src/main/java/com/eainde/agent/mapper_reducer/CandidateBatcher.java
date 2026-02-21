package com.eainde.agent.mapper_reducer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits large candidate lists into batches and merges results.
 *
 * <h3>Design principle:</h3>
 * <ul>
 *   <li>Input/output: JSON strings (agent boundary)</li>
 *   <li>Internal: Jackson tree model for structured splitting/merging</li>
 *   <li>No model DTOs imported — works entirely with JSON strings and JsonNode</li>
 * </ul>
 *
 * <h3>Used by BatchingReduceAgent:</h3>
 * <pre>
 * CandidateBatcher batcher = new CandidateBatcher(objectMapper, 50);
 *
 * if (batcher.countCandidates(json) > 50) {
 *     List&lt;String&gt; batches = batcher.splitCandidatesJson(json);
 *     List&lt;String&gt; results = batches.stream()
 *             .map(batch -&gt; runReduceWorkflow(batch))
 *             .toList();
 *     String merged = batcher.mergeEnrichedResultsJson(results);
 * }
 * </pre>
 */
public class CandidateBatcher {

    private static final Logger log = LoggerFactory.getLogger(CandidateBatcher.class);

    private final ObjectMapper objectMapper;
    private final int batchSize;

    /**
     * @param objectMapper shared Jackson ObjectMapper
     * @param batchSize    max candidates per batch (recommendation: 50)
     */
    public CandidateBatcher(ObjectMapper objectMapper, int batchSize) {
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be >= 1");
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
    }

    // =========================================================================
    //  Count
    // =========================================================================

    /**
     * Counts candidates in a JSON string.
     * Tries common key names: normalized_candidates, raw_candidates, candidates.
     *
     * @param json JSON string from agent output
     * @return candidate count, or 0 if parsing fails
     */
    public int countCandidates(String json) {
        if (json == null || json.isBlank()) return 0;
        try {
            JsonNode root = objectMapper.readTree(json);
            ArrayNode candidates = findCandidateArray(root,
                    "normalized_candidates", "raw_candidates",
                    "merged_candidates", "candidates");
            return candidates.size();
        } catch (Exception e) {
            log.warn("Failed to count candidates in JSON", e);
            return 0;
        }
    }

    // =========================================================================
    //  Split — String → List<String>
    // =========================================================================

    /**
     * Splits a candidates JSON string into batch-sized JSON strings.
     *
     * <p>Each batch is a complete JSON object with the same structure as the input,
     * but containing only a subset of candidates. Entities/NNPs are included only
     * in the first batch (global context).</p>
     *
     * @param normalizedCandidatesJson JSON from Agent 3 or Bridge
     * @return list of batch JSON strings, each <= batchSize candidates
     */
    public List<String> splitCandidatesJson(String normalizedCandidatesJson) {
        if (normalizedCandidatesJson == null || normalizedCandidatesJson.isBlank()) {
            return List.of(normalizedCandidatesJson != null ? normalizedCandidatesJson : "{}");
        }

        try {
            JsonNode root = objectMapper.readTree(normalizedCandidatesJson);

            // Find the candidate array key
            String candidateKey = findCandidateKey(root,
                    "normalized_candidates", "raw_candidates",
                    "merged_candidates", "candidates");
            ArrayNode allCandidates = findCandidateArray(root,
                    "normalized_candidates", "raw_candidates",
                    "merged_candidates", "candidates");

            if (allCandidates.size() <= batchSize) {
                return List.of(normalizedCandidatesJson);
            }

            // Find entities (NNPs) — included only in first batch
            ArrayNode entities = root.has("entities_found") && root.get("entities_found").isArray()
                    ? (ArrayNode) root.get("entities_found")
                    : objectMapper.createArrayNode();

            List<String> batches = new ArrayList<>();

            for (int start = 0; start < allCandidates.size(); start += batchSize) {
                int end = Math.min(start + batchSize, allCandidates.size());

                ObjectNode batchRoot = objectMapper.createObjectNode();

                // Slice candidates
                ArrayNode batchCandidates = objectMapper.createArrayNode();
                for (int i = start; i < end; i++) {
                    batchCandidates.add(allCandidates.get(i).deepCopy());
                }
                batchRoot.set(candidateKey, batchCandidates);

                // Entities only in first batch
                if (start == 0) {
                    batchRoot.set("entities_found", entities.deepCopy());
                } else {
                    batchRoot.set("entities_found", objectMapper.createArrayNode());
                }

                batches.add(objectMapper.writeValueAsString(batchRoot));
            }

            log.info("Split {} candidates into {} batches of up to {}",
                    allCandidates.size(), batches.size(), batchSize);
            return batches;

        } catch (JsonProcessingException e) {
            log.error("Failed to split candidates JSON — returning as single batch", e);
            return List.of(normalizedCandidatesJson);
        }
    }

    // =========================================================================
    //  Merge — List<String> → String (with id renumbering)
    // =========================================================================

    /**
     * Merges enriched candidate batch results into a single JSON string.
     * Renumbers ids sequentially per J4.
     *
     * @param batchResults list of enrichedCandidates JSON strings from per-batch runs
     * @return single merged JSON with sequential ids
     */
    public String mergeEnrichedResultsJson(List<String> batchResults) {
        return mergeResults(batchResults, "enriched_candidates", "candidates");
    }

    /**
     * Merges scored candidate batch results into a single JSON string.
     * Renumbers ids sequentially per J4.
     *
     * @param batchResults list of scoredCandidates JSON strings
     * @return single merged JSON with sequential ids
     */
    public String mergeScoringResultsJson(List<String> batchResults) {
        return mergeResults(batchResults, "scored_candidates", "candidates");
    }

    /**
     * Generic merge: concatenates candidate arrays, renumbers ids.
     */
    private String mergeResults(List<String> batchResults, String... possibleKeys) {
        ArrayNode allCandidates = objectMapper.createArrayNode();
        String detectedKey = possibleKeys[0]; // default

        for (String batchJson : batchResults) {
            if (batchJson == null || batchJson.isBlank()) continue;
            try {
                JsonNode root = objectMapper.readTree(batchJson);
                String key = findCandidateKey(root, possibleKeys);
                if (key != null) detectedKey = key;
                ArrayNode candidates = findCandidateArray(root, possibleKeys);
                for (JsonNode candidate : candidates) {
                    allCandidates.add(candidate.deepCopy());
                }
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse batch result, skipping", e);
            }
        }

        // Renumber ids sequentially (J4)
        for (int i = 0; i < allCandidates.size(); i++) {
            if (allCandidates.get(i).isObject()) {
                ((ObjectNode) allCandidates.get(i)).put("id", i + 1);
            }
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.set(detectedKey, allCandidates);

        log.info("Merged {} batches → {} total candidates (ids renumbered 1-{})",
                batchResults.size(), allCandidates.size(), allCandidates.size());

        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize merged candidates", e);
        }
    }

    // =========================================================================
    //  Internal Helpers
    // =========================================================================

    /**
     * Finds the candidate array key in a JSON root node.
     */
    private String findCandidateKey(JsonNode root, String... possibleKeys) {
        for (String key : possibleKeys) {
            if (root.has(key) && root.get(key).isArray()) {
                return key;
            }
        }
        return possibleKeys.length > 0 ? possibleKeys[0] : "candidates";
    }

    /**
     * Finds a candidate array inside a JSON node.
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

    public int getBatchSize() {
        return batchSize;
    }
}
