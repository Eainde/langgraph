package com.eainde.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Singleton batch accumulator tool with ThreadLocal-isolated state.
 *
 * <p>This is a Spring singleton bean — ONE instance shared by ALL agents.
 * Per-invocation state is isolated via ThreadLocal, so concurrent requests
 * never interfere with each other.</p>
 *
 * <h3>How it integrates (no wrapper needed):</h3>
 * <pre>
 * AgentSpec:
 *   .tools(List.of(batchAccumulatorTool))          ← singleton on spec
 *   .inputGuardrails(batchResetGuardrail, ...)     ← resets before each agent
 *   .outputGuardrails(..., batchMergerGuardrail)   ← merges after each agent
 *
 * Lifecycle per agent invocation:
 *   1. InputGuardrail  → tool.reset()               (fresh state)
 *   2. LLM runs        → tool.submitBatch() × N     (accumulates batches)
 *   3. OutputGuardrail  → tool.wasUsed()?            (check + merge + replace output)
 * </pre>
 *
 * <h3>ThreadLocal guarantees:</h3>
 * <ul>
 *   <li>Request A's batches are invisible to Request B</li>
 *   <li>Agent 1's batches are cleared before Agent 2 runs (by the input guardrail)</li>
 *   <li>No stale state — reset() is called before every agent invocation</li>
 * </ul>
 *
 * No. Same answer — the fallback handles it automatically.
 * The only scenario where you'd add an entry:
 * json// New agent outputs TWO arrays:
 * {
 *   "primary_results": [{ "id": 1, ... }, { "id": 2, ... }],
 *   "metadata_logs": ["log1", "log2", "log3"]
 * }
 * Without a knownKeys entry, the fallback picks the first array it finds. If metadata_logs happens to come first in the JSON, it merges the wrong array.
 * Adding "primary_results" to knownKeys forces it to pick the correct one.
 * If your new agent has only ONE array (which is the case for all 12 of your current agents), you don't need to touch knownKeys at all
 */
@Log4j2
@Component
public class BatchAccumulatorTool {

    private final ObjectMapper objectMapper;

    // ── ThreadLocal state: isolated per request thread ──────────────────
    private final ThreadLocal<List<String>> batches =
            ThreadLocal.withInitial(ArrayList::new);
    private final ThreadLocal<Integer> totalRecordCount =
            ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Integer> batchCount =
            ThreadLocal.withInitial(() -> 0);

    public BatchAccumulatorTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    //  State Management — called by guardrails
    // =========================================================================

    /**
     * Resets state for the current thread. Called by {@code BatchResetInputGuardrail}
     * BEFORE each agent invocation.
     */
    public void reset() {
        batches.get().clear();
        totalRecordCount.set(0);
        batchCount.set(0);
    }

    /**
     * Whether the LLM used batching in the current invocation.
     * Called by {@code BatchMergerOutputGuardrail} AFTER the agent completes.
     */
    public boolean wasUsed() {
        return !batches.get().isEmpty();
    }

    /**
     * Number of batches submitted in the current invocation.
     */
    public int getBatchCount() {
        return batchCount.get();
    }

    /**
     * Total records across all batches in the current invocation.
     */
    public int getTotalRecordCount() {
        return totalRecordCount.get();
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
        You MUST use this tool when your output would contain more than 40 records.
        Call this repeatedly with batches of ~40 records each.
        Use the same JSON schema as your normal output.
        Maintain sequential ID numbering across batches.
        After the last batch, return ONLY a text summary, NOT JSON.""")
    public String submitBatch(
            @P("JSON object containing the batch of records, same schema as normal output")
            String jsonBatch) {

        int currentBatch = batchCount.get() + 1;
        batchCount.set(currentBatch);
        batches.get().add(jsonBatch);

        int batchRecords = countRecords(jsonBatch);
        int runningTotal = totalRecordCount.get() + batchRecords;
        totalRecordCount.set(runningTotal);

        log.info("Batch {} received: {} records (running total: {})",
                currentBatch, batchRecords, runningTotal);

        return String.format(
                "Batch %d received: %d records (%d total so far). "
                        + "If there are more records to extract, continue calling submit_batch "
                        + "with the next batch. Maintain sequential IDs (next id starts at %d). "
                        + "If all records have been submitted, return ONLY a text summary.",
                currentBatch, batchRecords, runningTotal, runningTotal + 1);
    }

    // =========================================================================
    //  Merge — called by BatchMergerOutputGuardrail
    // =========================================================================

    /**
     * Merges all accumulated batches into a single JSON result.
     *
     * <p>Finds the primary array field in each batch, concatenates all arrays,
     * and renumbers IDs sequentially.</p>
     *
     * @return merged JSON string with all records and sequential IDs
     */
    public String getMergedResult() {
        List<String> currentBatches = batches.get();

        if (currentBatches.isEmpty()) {
            return "{}";
        }

        if (currentBatches.size() == 1) {
            return currentBatches.get(0);
        }

        try {
            return mergeBatches(currentBatches);
        } catch (Exception e) {
            log.error("Failed to merge {} batches — concatenating raw JSON",
                    currentBatches.size(), e);
            return fallbackConcatenate(currentBatches);
        }
    }

    // =========================================================================
    //  Merge Logic
    // =========================================================================

    private String mergeBatches(List<String> batchList) throws JsonProcessingException {
        JsonNode firstBatch = objectMapper.readTree(batchList.get(0));
        String arrayKey = findPrimaryArrayKey(firstBatch);

        if (arrayKey == null) {
            log.warn("No array field found in batch output — falling back to concatenation");
            return fallbackConcatenate(batchList);
        }

        ArrayNode mergedArray = objectMapper.createArrayNode();

        for (String batchJson : batchList) {
            JsonNode batch = objectMapper.readTree(batchJson);
            JsonNode arrayNode = batch.get(arrayKey);
            if (arrayNode != null && arrayNode.isArray()) {
                for (JsonNode record : arrayNode) {
                    mergedArray.add(record.deepCopy());
                }
            }
        }

        renumberIds(mergedArray);

        ObjectNode result = firstBatch.deepCopy();
        result.set(arrayKey, mergedArray);

        // Copy non-array fields from last batch (most complete metadata)
        JsonNode lastBatch = objectMapper.readTree(batchList.get(batchList.size() - 1));
        copyNonArrayFields(lastBatch, result, arrayKey);

        log.info("Merged {} batches: {} total records under key '{}'",
                batchList.size(), mergedArray.size(), arrayKey);

        return objectMapper.writeValueAsString(result);
    }

    private String findPrimaryArrayKey(JsonNode root) {
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

        var fields = root.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (entry.getValue().isArray()) {
                return entry.getKey();
            }
        }

        return null;
    }

    private void renumberIds(ArrayNode records) {
        for (int i = 0; i < records.size(); i++) {
            JsonNode record = records.get(i);
            if (record.isObject() && record.has("id")) {
                ((ObjectNode) record).put("id", i + 1);
            }
        }
    }

    private void copyNonArrayFields(JsonNode source, ObjectNode target, String skipArrayKey) {
        var fields = source.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (!entry.getKey().equals(skipArrayKey) && !entry.getValue().isArray()) {
                target.set(entry.getKey(), entry.getValue().deepCopy());
            }
        }
    }

    private String fallbackConcatenate(List<String> batchList) {
        StringBuilder sb = new StringBuilder("{\"merged_batches\":[");
        for (int i = 0; i < batchList.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(batchList.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

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