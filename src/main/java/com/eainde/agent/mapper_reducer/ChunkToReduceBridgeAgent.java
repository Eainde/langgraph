package com.eainde.agent.mapper_reducer;

import com.db.clm.kyc.ai.model.MergedResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.scope.AgenticScope;
import lombok.extern.log4j.Log4j2;

/**
 * Bridge between MERGE and REDUCE phases.
 *
 * <p>The Chunk Merger Agent (LLM) writes {@code mergedResult} with its own structure.
 * The REDUCE phase agents (starting with Agent 4: Dedup+Linker) expect:
 * <ul>
 *   <li>{@code normalizedCandidates} — same key Agent 3 writes in the direct path</li>
 *   <li>{@code sourceClassification} — global merged ranking</li>
 * </ul>
 *
 * <p>This bridge translates the merger's output so REDUCE agents work identically
 * regardless of whether they run in direct or chunked path.</p>
 *
 * <pre>
 * invoke(scope):
 *   mergedResult = scope["mergedResult"]
 *
 *   // DTO boundary: parse to extract what Agent 4 needs
 *   MergedResult dto = parse(mergedResult)
 *
 *   scope["normalizedCandidates"] = dto.mergedCandidates        ← Agent 4 reads this
 *   scope["sourceClassification"] = dto.globalSourceClassification  ← Agent 4, 5, 6 read this
 * </pre>
 */
@Log4j2
public class ChunkToReduceBridgeAgent implements UntypedAgent {

    private final ObjectMapper objectMapper;

    public ChunkToReduceBridgeAgent(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void invoke(AgenticScope scope) {
        log.info("BRIDGE — translating mergedResult for REDUCE phase");

        Object mergedResultObj = scope.readState("mergedResult");
        if (mergedResultObj == null) {
            throw new IllegalStateException(
                    "mergedResult not found in scope — Chunk Merger Agent must write it");
        }
        String mergedResultJson = mergedResultObj.toString();

        try {
            // ── DTO boundary: structured access to merger output ─────────
            MergedResult mergedResult = objectMapper.readValue(
                    mergedResultJson, MergedResult.class);

            // ── Build normalizedCandidates JSON ─────────────────────────
            // Agent 4 expects: {"normalized_candidates": [...]}
            // Merger output has: mergedCandidates (same structure as normalized)
            String normalizedCandidatesJson = buildNormalizedCandidatesJson(
                    mergedResult);

            // ── Build global sourceClassification JSON ──────────────────
            // Agents 4, 5, 6 expect: {"source_classification": [...]}
            // Merger output has: globalSourceClassification (same structure)
            String sourceClassificationJson = buildSourceClassificationJson(
                    mergedResult);

            // ── Write to scope (overwrite per-chunk values with global) ─
            scope.writeState("normalizedCandidates", normalizedCandidatesJson);
            scope.writeState("sourceClassification", sourceClassificationJson);

            int candidateCount = mergedResult.mergedCandidates() != null
                    ? mergedResult.mergedCandidates().size() : 0;

            log.info("BRIDGE complete — {} deduped candidates, {} sources written to scope",
                    candidateCount,
                    mergedResult.globalSourceClassification() != null
                            ? mergedResult.globalSourceClassification().size() : 0);

            if (mergedResult.mergeStats() != null) {
                log.info("Merge stats — before: {}, after: {}, duplicates removed: {}, overlap dupes: {}",
                        mergedResult.mergeStats().totalCandidatesBeforeMerge(),
                        mergedResult.mergeStats().totalCandidatesAfterMerge(),
                        mergedResult.mergeStats().duplicatesRemoved(),
                        mergedResult.mergeStats().overlapDuplicates());
            }

        } catch (JsonProcessingException e) {
            log.error("Failed to parse mergedResult — passing raw JSON to Agent 4", e);
            // Fallback: write the raw merged result as normalizedCandidates
            // Agent 4 may still be able to work with it
            scope.writeState("normalizedCandidates", mergedResultJson);
        }
    }

    /**
     * Builds the normalizedCandidates JSON in the format Agent 4 expects.
     * Re-serializes merged candidates from the DTO.
     */
    private String buildNormalizedCandidatesJson(MergedResult mergedResult)
            throws JsonProcessingException {
        // Wrap merged candidates in the structure Agent 3 would produce
        // Agent 4 reads "normalized_candidates" key
        var wrapper = new java.util.LinkedHashMap<String, Object>();
        wrapper.put("normalized_candidates",
                mergedResult.mergedCandidates() != null
                        ? mergedResult.mergedCandidates()
                        : java.util.List.of());
        // Pass through entities if present
        wrapper.put("entities_found", java.util.List.of());
        return objectMapper.writeValueAsString(wrapper);
    }

    /**
     * Builds the sourceClassification JSON in the format agents expect.
     * Re-serializes global source classification from the DTO.
     */
    private String buildSourceClassificationJson(MergedResult mergedResult)
            throws JsonProcessingException {
        var wrapper = new java.util.LinkedHashMap<String, Object>();
        wrapper.put("source_classification",
                mergedResult.globalSourceClassification() != null
                        ? mergedResult.globalSourceClassification()
                        : java.util.List.of());
        return objectMapper.writeValueAsString(wrapper);
    }
}