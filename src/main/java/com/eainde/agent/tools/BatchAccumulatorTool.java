package com.eainde.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayList;
import java.util.List;

/**
 * Generic batch accumulator tool for LLM-driven pagination.
 *
 * <p>When an agent's output exceeds the model's output token limit, the LLM
 * calls this tool repeatedly to submit results in manageable batches. The tool
 * accumulates all batches in memory, and after the agent completes, the caller
 * reads the merged result.</p>
 *
 * <h3>Lifecycle:</h3>
 * <pre>
 * new BatchAccumulatorTool(objectMapper)     ← created fresh per agent invocation
 *   → LLM calls submit_batch(batch1)        ← accumulates
 *   → LLM calls submit_batch(batch2)        ← accumulates
 *   → LLM calls submit_batch(batch3)        ← accumulates
 *   → LLM returns final text response
 *   → caller reads wasUsed() + getMergedResult()
 *   → tool is discarded (garbage collected)
 * </pre>
 *
 * <h3>NOT a Spring bean. Created with {@code new}. No shared state.</h3>
 */
@Log4j2
public class BatchAccumulatorTool {

    private final ObjectMapper objectMapper;
    private final List<String> batches = new ArrayList<>();
    private int totalRecordCount = 0;
    private int batchCount = 0;

    public BatchAccumulatorTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    //  @Tool method — called by the LLM via LangChain4j tool loop
    // =========================================================================

    /**
     * Submit a batch of extracted records. Call this tool when your output
     * contains more than 40 records. Submit records in batches of ~40,
     * continuing until all records have been submitted.
     *
     * @param jsonBatch a JSON object containing the batch of records.
     *                  Use the same schema as your normal output.
     *                  Example: {"raw_names": [{"id": 1, ...}, {"id": 2, ...}]}
     * @return acknowledgment with running total — continue extracting if more remain
     */
    @Tool("""
        Submit a batch of extracted records when your output is large.
        Call this repeatedly with batches of ~40 records each.
        Use the same JSON schema as your normal output.
        Continue calling until all records are submitted.""")
    public String submitBatch(
            @P("JSON object containing the batch of records, same schema as normal output")
            String jsonBatch) {

        batchCount++;
        batches.add(jsonBatch);

        // Count records in this batch
        int batchRecords = countRecords(jsonBatch);
        totalRecordCount += batchRecords;

        log.info("Batch {} received: {} records (running total: {})",
                batchCount, batchRecords, totalRecordCount);

        return String.format(
                "Batch %d received: %d records (%d total so far). "
                        + "If there are more records to extract, continue calling submit_batch. "
                        + "If all records have been submitted, return a summary text "
                        + "(do NOT return JSON — just confirm completion).",
                batchCount, batchRecords, totalRecordCount);
    }

    // =========================================================================
    //  Read methods — called by BatchingAgentWrapper after agent completes
    // =========================================================================

    /**
     * Whether the LLM used batching (called submit_batch at least once).
     * If false, the agent returned everything in a single response.
     */
    public boolean wasUsed() {
        return !batches.isEmpty();
    }

    /**
     * Number of batches submitted.
     */
    public int getBatchCount() {
        return batchCount;
    }

    /**
     * Total records across all batches.
     */
    public int getTotalRecordCount() {
        return totalRecordCount;
    }

    /**
     * Merges all accumulated batches into a single JSON result.
     *
     * <p>Strategy: finds the first array field in each batch's JSON object,
     * concatenates all arrays, and renumbers IDs sequentially.</p>
     *
     * <p>Example:</p>
     * <pre>
     * Batch 1: {"raw_names": [{"id":1,...}, {"id":2,...}]}
     * Batch 2: {"raw_names": [{"id":1,...}, {"id":2,...}]}  ← LLM may restart IDs
     * Merged:  {"raw_names": [{"id":1,...}, {"id":2,...}, {"id":3,...}, {"id":4,...}]}
     * </pre>
     *
     * @return merged JSON string with all records and sequential IDs
     */
    public String getMergedResult() {
        if (batches.isEmpty()) {
            return "{}";
        }

        // Single batch — return as-is (no merge needed)
        if (batches.size() == 1) {
            return batches.get(0);
        }

        try {
            return mergeBatches();
        } catch (Exception e) {
            log.error("Failed to merge {} batches — concatenating raw JSON", batchCount, e);
            return fallbackConcatenate();
        }
    }

    // =========================================================================
    //  Merge Logic
    // =========================================================================

    /**
     * Merges batches by finding the primary record array in each batch
     * and concatenating them into a single array with sequential IDs.
     */
    private String mergeBatches() throws JsonProcessingException {
        // Parse first batch to determine the structure (which key holds the array)
        JsonNode firstBatch = objectMapper.readTree(batches.get(0));
        String arrayKey = findPrimaryArrayKey(firstBatch);

        if (arrayKey == null) {
            log.warn("No array field found in batch output — falling back to concatenation");
            return fallbackConcatenate();
        }

        // Collect all records from all batches
        ArrayNode mergedArray = objectMapper.createArrayNode();

        for (String batchJson : batches) {
            JsonNode batch = objectMapper.readTree(batchJson);

            JsonNode arrayNode = batch.get(arrayKey);
            if (arrayNode != null && arrayNode.isArray()) {
                for (JsonNode record : arrayNode) {
                    mergedArray.add(record.deepCopy());
                }
            }
        }

        // Renumber IDs sequentially (LLM may restart IDs per batch)
        renumberIds(mergedArray);

        // Build the merged result using the same top-level structure as batch 1
        ObjectNode result = firstBatch.deepCopy();
        result.set(arrayKey, mergedArray);

        // Copy any non-array fields from the LAST batch (most complete metadata)
        JsonNode lastBatch = objectMapper.readTree(batches.get(batches.size() - 1));
        copyNonArrayFields(lastBatch, result, arrayKey);

        log.info("Merged {} batches: {} total records under key '{}'",
                batchCount, mergedArray.size(), arrayKey);

        return objectMapper.writeValueAsString(result);
    }

    /**
     * Finds the first array field in a JSON object — this is the "records" array.
     *
     * <p>Checks known keys first (raw_names, normalized_candidates, etc.),
     * then falls back to the first array field found.</p>
     */
    private String findPrimaryArrayKey(JsonNode root) {
        // Check known candidate array keys in priority order
        String[] knownKeys = {
                "raw_names", "entities_found",
                "normalized_candidates", "deduped_candidates",
                "classified_candidates", "country_overrides",
                "title_extractions", "scored_candidates",
                "enriched_candidates", "reasoned_candidates",
                "extracted_records", "candidates",
                "source_classification"
        };

        for (String key : knownKeys) {
            if (root.has(key) && root.get(key).isArray()) {
                return key;
            }
        }

        // Fallback: first array field
        var fields = root.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (entry.getValue().isArray()) {
                return entry.getKey();
            }
        }

        return null;
    }

    /**
     * Renumbers the "id" field sequentially across all merged records.
     */
    private void renumberIds(ArrayNode records) {
        for (int i = 0; i < records.size(); i++) {
            JsonNode record = records.get(i);
            if (record.isObject() && record.has("id")) {
                ((ObjectNode) record).put("id", i + 1);
            }
        }
    }

    /**
     * Copies non-array scalar fields from source to target
     * (e.g., metadata fields like "extraction_summary", "total_count").
     */
    private void copyNonArrayFields(JsonNode source, ObjectNode target, String skipArrayKey) {
        var fields = source.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (!entry.getKey().equals(skipArrayKey) && !entry.getValue().isArray()) {
                target.set(entry.getKey(), entry.getValue().deepCopy());
            }
        }
    }

    /**
     * Fallback: if structured merge fails, concatenate batch strings.
     * This preserves data even if JSON structure is unexpected.
     */
    private String fallbackConcatenate() {
        StringBuilder sb = new StringBuilder("{\"merged_batches\":[");
        for (int i = 0; i < batches.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(batches.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    /**
     * Counts records in a JSON batch by finding the first array and returning its size.
     */
    private int countRecords(String jsonBatch) {
        try {
            JsonNode root = objectMapper.readTree(jsonBatch);
            String arrayKey = findPrimaryArrayKey(root);
            if (arrayKey != null) {
                return root.get(arrayKey).size();
            }
            if (root.isArray()) {
                return root.size();
            }
        } catch (Exception e) {
            log.debug("Could not count records in batch: {}", e.getMessage());
        }
        return 0;
    }
}