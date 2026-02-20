package com.eainde.agent.V2.chunk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits large candidate arrays into batches for LLM processing and merges
 * the results back together with correct sequential id numbering.
 *
 * <h3>When to use</h3>
 * <p>After chunk merging, if the total number of candidates exceeds what the
 * LLM can process in a single call (output token limit), batch through
 * agents 3-4 (classifier + scorer) which are per-person operations.</p>
 *
 * <h3>Usage:</h3>
 * <pre>
 * CandidateBatcher batcher = new CandidateBatcher(objectMapper, 50);
 *
 * if (batcher.needsBatching(mergedCandidatesJson)) {
 *     List&lt;String&gt; batches = batcher.split(mergedCandidatesJson, "raw_candidates");
 *     List&lt;String&gt; results = batches.stream()
 *             .map(batch -&gt; runClassifierAndScorer(batch))
 *             .toList();
 *     String merged = batcher.mergeResults(results, "scored_candidates");
 * }
 * </pre>
 */
public class CandidateBatcher {

    private static final Logger log = LoggerFactory.getLogger(CandidateBatcher.class);

    private final ObjectMapper objectMapper;
    private final int batchSize;

    /**
     * @param objectMapper shared Jackson ObjectMapper
     * @param batchSize    max records per batch (default recommendation: 50)
     */
    public CandidateBatcher(ObjectMapper objectMapper, int batchSize) {
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be >= 1");
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
    }

    /**
     * Creates a batcher with default batch size of 50.
     */
    public CandidateBatcher(ObjectMapper objectMapper) {
        this(objectMapper, 50);
    }

    // =========================================================================
    //  Public API
    // =========================================================================

    /**
     * Determines whether the candidates JSON needs batching.
     *
     * @param candidatesJson JSON string containing an array of candidates
     * @param arrayKey       the JSON key containing the array (e.g., "raw_candidates", "classified_candidates")
     * @return true if the array has more records than batchSize
     */
    public boolean needsBatching(String candidatesJson, String arrayKey) {
        try {
            JsonNode root = objectMapper.readTree(candidatesJson);
            JsonNode array = root.has(arrayKey) ? root.get(arrayKey) : root;
            if (!array.isArray()) return false;
            boolean needs = array.size() > batchSize;
            if (needs) {
                log.info("Candidates need batching: {} records exceeds batch size {}",
                        array.size(), batchSize);
            }
            return needs;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse candidates JSON for batching check", e);
            return false;
        }
    }

    /**
     * Splits a candidates JSON into batches.
     *
     * <p>Each batch is a valid JSON object with the same structure as the input,
     * containing a subset of the array. Original {@code id} fields are preserved
     * within each batch (renumbering happens at merge time).</p>
     *
     * @param candidatesJson the full JSON string
     * @param arrayKey       the JSON key containing the array
     * @return list of JSON strings, each containing up to batchSize records
     */
    public List<String> split(String candidatesJson, String arrayKey) {
        try {
            JsonNode root = objectMapper.readTree(candidatesJson);
            JsonNode array = root.has(arrayKey) ? root.get(arrayKey) : root;

            if (!array.isArray()) {
                log.warn("Expected array at key '{}', returning input as single batch", arrayKey);
                return List.of(candidatesJson);
            }

            int total = array.size();
            List<String> batches = new ArrayList<>();

            for (int start = 0; start < total; start += batchSize) {
                int end = Math.min(start + batchSize, total);

                ArrayNode batchArray = objectMapper.createArrayNode();
                for (int i = start; i < end; i++) {
                    batchArray.add(array.get(i));
                }

                // Wrap in same structure as original
                ObjectNode batchRoot = objectMapper.createObjectNode();
                batchRoot.set(arrayKey, batchArray);

                // Copy any non-array fields from the original (e.g., metadata)
                root.fieldNames().forEachRemaining(field -> {
                    if (!field.equals(arrayKey)) {
                        batchRoot.set(field, root.get(field));
                    }
                });

                batches.add(objectMapper.writeValueAsString(batchRoot));
            }

            log.info("Split {} records into {} batches of up to {}", total, batches.size(), batchSize);
            return batches;

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to split candidates into batches", e);
        }
    }

    /**
     * Merges batch results back together and renumbers {@code id} fields
     * sequentially starting from 1, preserving the original batch order.
     *
     * <p>This ensures J4 compliance: records are ordered by document → page → reading
     * order, with sequential ids across the entire result set.</p>
     *
     * @param batchResults list of JSON strings from per-batch processing
     * @param arrayKey     the JSON key containing the results array
     * @return single merged JSON string with renumbered ids
     */
    public String mergeResults(List<String> batchResults, String arrayKey) {
        try {
            ArrayNode merged = objectMapper.createArrayNode();
            ObjectNode metadataSource = null;

            for (String batchJson : batchResults) {
                JsonNode root = objectMapper.readTree(batchJson);
                JsonNode array = root.has(arrayKey) ? root.get(arrayKey) : root;

                if (array.isArray()) {
                    for (JsonNode record : array) {
                        merged.add(record.deepCopy());
                    }
                }

                // Capture metadata from first batch
                if (metadataSource == null && root.isObject()) {
                    metadataSource = (ObjectNode) root.deepCopy();
                }
            }

            // Renumber ids sequentially (J4)
            int id = 1;
            for (JsonNode record : merged) {
                if (record.isObject()) {
                    ((ObjectNode) record).put("id", id++);
                }
            }

            // Build result with same structure
            ObjectNode result = metadataSource != null
                    ? metadataSource
                    : objectMapper.createObjectNode();
            result.set(arrayKey, merged);

            log.info("Merged {} batches into {} total records", batchResults.size(), merged.size());
            return objectMapper.writeValueAsString(result);

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to merge batch results", e);
        }
    }

    /**
     * Convenience: merge results from the "extracted_records" key and renumber.
     */
    public String mergeFinalOutputs(List<String> batchResults) {
        return mergeResults(batchResults, "extracted_records");
    }

    // =========================================================================
    //  Getters
    // =========================================================================

    public int getBatchSize() {
        return batchSize;
    }
}
