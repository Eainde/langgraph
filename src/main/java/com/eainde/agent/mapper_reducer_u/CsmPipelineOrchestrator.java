package com.eainde.agent.mapper_reducer_u;

package com.db.clm.kyc.ai.agents;

import com.db.clm.kyc.ai.chunking.CandidateBatcher;
import com.db.clm.kyc.ai.chunking.ChunkContext;
import com.db.clm.kyc.ai.chunking.DocumentChunker;
import com.db.clm.kyc.ai.model.MergedResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agentic.ResultWithAgenticScope;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.scope.AgenticScope;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level orchestrator for CSM extraction pipeline V6.
 *
 * <h3>Routing:</h3>
 * <ul>
 *   <li><b>Small doc</b> → Direct workflow (single sequence, framework manages scope)</li>
 *   <li><b>Large doc</b> → Chunked workflow (manual MAP/MERGE/REDUCE/CRITIC orchestration)</li>
 * </ul>
 *
 * <h3>Direct path:</h3>
 * <pre>
 * directWorkflow.invoke(Map.of("sourceText", text, "fileNames", files))
 * → framework runs: 1→2→3→4→5→6→7→8→Wave5Merge→9→10→11→Loop(12→11)
 * → returns finalOutput
 * </pre>
 *
 * <h3>Chunked path:</h3>
 * <pre>
 * MAP:    per chunk → mapSequence.invokeWithAgenticScope() → collect from scope
 * MERGE:  chunkMerger.invoke(chunkResults) → mergedResult
 * BRIDGE: Java extracts normalizedCandidates + sourceClassification from mergedResult
 * REDUCE: per batch → reduceSequence.invokeWithAgenticScope() → collect enrichedCandidates
 *         merge batches → single enrichedCandidates (J4 id renumbering)
 * TAIL:   tailSequence.invoke(enrichedCandidates, fileNames) → finalOutput
 * CRITIC: manual loop → critic.invoke() / refiner.invoke() → exit when score ≥ 0.85
 * </pre>
 *
 * <h3>Usage from LangGraph node or service:</h3>
 * <pre>
 * String result = orchestrator.execute(sourceText, fileNames);
 * ExtractionOutput output = objectMapper.readValue(result, ExtractionOutput.class);
 * </pre>
 */
@Log4j2
@Component
public class CsmPipelineOrchestrator {

    private final CsmExtractionWorkflowConfigV6 config;
    private final ObjectMapper objectMapper;

    public CsmPipelineOrchestrator(CsmExtractionWorkflowConfigV6 config) {
        this.config = config;
        this.objectMapper = config.getObjectMapper();
    }

    // =========================================================================
    //  Public API
    // =========================================================================

    /**
     * Executes CSM extraction — routes to direct or chunked path.
     *
     * @param sourceText raw document text (all documents concatenated)
     * @param fileNames  comma-separated document file names
     * @return finalOutput JSON string (ExtractionOutput schema)
     */
    public String execute(String sourceText, String fileNames) {
        boolean needsChunking = config.isChunkingEnabled()
                && DocumentChunker.estimateTokens(sourceText) > config.getMaxTokenEstimate();

        if (!needsChunking) {
            log.info("DIRECT path — document fits within token limit ({} estimated tokens)",
                    DocumentChunker.estimateTokens(sourceText));
            return executeDirect(sourceText, fileNames);
        } else {
            log.info("CHUNKED path — document exceeds token limit ({} > {})",
                    DocumentChunker.estimateTokens(sourceText),
                    config.getMaxTokenEstimate());
            return executeChunked(sourceText, fileNames);
        }
    }

    // =========================================================================
    //  Direct Path — single sequence invocation
    // =========================================================================

    /**
     * Direct path: invokes the full 12-agent sequence + critic loop.
     * Framework manages AgenticScope internally — we just pass initial inputs.
     */
    private String executeDirect(String sourceText, String fileNames) {
        log.info("Executing direct workflow — 12 agents + critic loop");

        Object result = config.getDirectWorkflow().invoke(
                Map.of("sourceText", sourceText, "fileNames", fileNames));

        log.info("Direct workflow complete");
        return result != null ? result.toString() : "{}";
    }

    // =========================================================================
    //  Chunked Path — manual orchestration
    // =========================================================================

    /**
     * Chunked path: MAP → MERGE → BRIDGE → REDUCE (batching) → TAIL → CRITIC.
     * Each phase invokes sub-workflows manually, carrying state in Java variables.
     */
    private String executeChunked(String sourceText, String fileNames) {

        // ── STEP 1: Chunk the document ──────────────────────────────────
        DocumentChunker chunker = DocumentChunker.builder()
                .pagesPerChunk(config.getPagesPerChunk())
                .overlapPages(config.getOverlapPages())
                .pageDelimiter(config.getPageDelimiterRegex())
                .build();

        List<ChunkContext> chunks = chunker.chunk(sourceText);
        log.info("Document split into {} chunks ({}pp each, {}pp overlap)",
                chunks.size(), config.getPagesPerChunk(), config.getOverlapPages());

        // ── STEP 2: MAP — per-chunk agents 1→2→3 ───────────────────────
        List<ChunkOutput> chunkOutputs = executeMapPhase(chunks, fileNames);

        // ── STEP 3: MERGE — LLM chunk merger ───────────────────────────
        String chunkResultsJson = buildChunkResultsJson(chunkOutputs);
        log.info("Invoking chunk merger agent");
        Object mergedResultRaw = config.getChunkMerger().invoke(
                Map.of("chunkResults", chunkResultsJson));
        String mergedResultJson = mergedResultRaw != null ? mergedResultRaw.toString() : "{}";

        // ── STEP 4: BRIDGE — extract what REDUCE agents need ───────────
        BridgeOutput bridge = executeBridge(mergedResultJson);

        // ── STEP 5: REDUCE — agents 4→5→6→7→8→Wave5Merge (with batching) ─
        String enrichedCandidates = executeReducePhase(
                bridge.normalizedCandidates(),
                bridge.sourceClassification(),
                sourceText);

        // ── STEP 6: TAIL — agents 9→10 (always on full set) ───────────
        String finalOutput = executeTailPhase(enrichedCandidates, fileNames);

        // ── STEP 7: CRITIC LOOP — agents 11→12 (manual loop) ──────────
        return executeCriticLoop(finalOutput, sourceText, enrichedCandidates);
    }

    // =========================================================================
    //  MAP Phase — per chunk
    // =========================================================================

    /**
     * Runs agents 1→2→3 per chunk using mapSequence.invokeWithAgenticScope().
     * Each invocation gets its own scope. We read intermediate outputs from scope.
     */
    private List<ChunkOutput> executeMapPhase(List<ChunkContext> chunks, String fileNames) {
        log.info("MAP phase — running agents 1-3 per chunk");

        UntypedAgent mapSequence = config.getMapSequence();
        List<ChunkOutput> outputs = new ArrayList<>();

        for (ChunkContext chunk : chunks) {
            log.info("MAP — chunk {}/{}: pages {}-{}",
                    chunk.chunkIndex() + 1, chunks.size(),
                    chunk.pageStart(), chunk.pageEnd());

            // Invoke map sequence with this chunk's text
            // Each invocation creates a fresh scope
            ResultWithAgenticScope<String> result = mapSequence.invokeWithAgenticScope(
                    Map.of("sourceText", chunk.chunkText(),
                            "fileNames", fileNames));

            // Read intermediate outputs from the scope
            AgenticScope scope = result.agenticScope();
            String rawNames               = readScopeString(scope, "rawNames");
            String sourceClassification   = readScopeString(scope, "sourceClassification");
            String normalizedCandidates   = readScopeString(scope, "normalizedCandidates");

            outputs.add(new ChunkOutput(chunk, rawNames, sourceClassification, normalizedCandidates));

            log.info("MAP — chunk {}/{} complete", chunk.chunkIndex() + 1, chunks.size());
        }

        log.info("MAP phase complete — {} chunks processed", outputs.size());
        return outputs;
    }

    // =========================================================================
    //  BRIDGE — mergedResult → normalizedCandidates + sourceClassification
    // =========================================================================

    /**
     * Translates chunk merger output into the format REDUCE agents expect.
     * Agent 4 reads "normalizedCandidates" and "sourceClassification" —
     * same keys Agent 3 writes in the direct path.
     */
    private BridgeOutput executeBridge(String mergedResultJson) {
        log.info("BRIDGE — translating mergedResult for REDUCE phase");

        try {
            MergedResult merged = objectMapper.readValue(mergedResultJson, MergedResult.class);

            // Build normalizedCandidates: {"normalized_candidates": [...]}
            var candidatesWrapper = new LinkedHashMap<String, Object>();
            candidatesWrapper.put("normalized_candidates",
                    merged.mergedCandidates() != null ? merged.mergedCandidates() : List.of());
            candidatesWrapper.put("entities_found", List.of());
            String normalizedCandidates = objectMapper.writeValueAsString(candidatesWrapper);

            // Build sourceClassification: {"source_classification": [...]}
            var sourceWrapper = new LinkedHashMap<String, Object>();
            sourceWrapper.put("source_classification",
                    merged.globalSourceClassification() != null
                            ? merged.globalSourceClassification() : List.of());
            String sourceClassification = objectMapper.writeValueAsString(sourceWrapper);

            int candidateCount = merged.mergedCandidates() != null
                    ? merged.mergedCandidates().size() : 0;

            if (merged.mergeStats() != null) {
                log.info("BRIDGE — {} candidates (before: {}, removed: {}, overlap dupes: {})",
                        candidateCount,
                        merged.mergeStats().totalCandidatesBeforeMerge(),
                        merged.mergeStats().duplicatesRemoved(),
                        merged.mergeStats().overlapDuplicates());
            }

            return new BridgeOutput(normalizedCandidates, sourceClassification);

        } catch (JsonProcessingException e) {
            log.error("BRIDGE — failed to parse mergedResult, passing raw JSON", e);
            return new BridgeOutput(mergedResultJson, "{}");
        }
    }

    // =========================================================================
    //  REDUCE Phase — with optional batching
    // =========================================================================

    /**
     * Runs agents 4→5→6→7→8→Wave5Merge on the merged candidates.
     * If candidate count exceeds batch size, splits into batches and merges results.
     */
    private String executeReducePhase(String normalizedCandidates,
                                      String sourceClassification,
                                      String sourceText) {
        log.info("REDUCE phase — agents 4-8 + Wave5Merge");

        CandidateBatcher batcher = new CandidateBatcher(objectMapper, config.getBatchSize());
        int candidateCount = batcher.countCandidates(normalizedCandidates);

        if (!config.isBatchingEnabled() || candidateCount <= config.getBatchSize()) {
            // ── Single pass ─────────────────────────────────────────────
            log.info("REDUCE — single pass ({} candidates)", candidateCount);
            return executeSingleReduce(normalizedCandidates, sourceClassification, sourceText);
        }

        // ── Batched execution ───────────────────────────────────────────
        List<String> batches = batcher.splitCandidatesJson(normalizedCandidates);
        log.info("REDUCE — batching {} candidates into {} batches of {}",
                candidateCount, batches.size(), config.getBatchSize());

        List<String> batchResults = new ArrayList<>();

        for (int i = 0; i < batches.size(); i++) {
            log.info("REDUCE — batch {}/{}", i + 1, batches.size());

            String batchEnriched = executeSingleReduce(
                    batches.get(i), sourceClassification, sourceText);
            batchResults.add(batchEnriched);

            log.info("REDUCE — batch {}/{} complete", i + 1, batches.size());
        }

        // Merge batch results + renumber ids (J4)
        String merged = batcher.mergeEnrichedResultsJson(batchResults);
        log.info("REDUCE complete — {} batches merged", batches.size());
        return merged;
    }

    /**
     * Single REDUCE pass: invokes reduceSequence (agents 4-8 + Wave5Merge)
     * and reads enrichedCandidates from the resulting scope.
     */
    private String executeSingleReduce(String normalizedCandidates,
                                       String sourceClassification,
                                       String sourceText) {
        ResultWithAgenticScope<String> result = config.getReduceSequence()
                .invokeWithAgenticScope(Map.of(
                        "normalizedCandidates", normalizedCandidates,
                        "sourceClassification", sourceClassification,
                        "sourceText", sourceText));

        AgenticScope scope = result.agenticScope();

        // Read enrichedCandidates — written by Wave5MergerAgent (last in sequence)
        String enrichedCandidates = readScopeString(scope, "enrichedCandidates");

        // If enrichedCandidates is empty, fall back to sequence result
        if (enrichedCandidates.isBlank() && result.result() != null) {
            log.warn("enrichedCandidates not found in scope — using sequence result");
            enrichedCandidates = result.result();
        }

        return enrichedCandidates;
    }

    // =========================================================================
    //  TAIL Phase — agents 9→10 on full set
    // =========================================================================

    /**
     * Post-reduce: Reason Assembly → Output Formatting on full enriched set.
     */
    private String executeTailPhase(String enrichedCandidates, String fileNames) {
        log.info("TAIL phase — agents 9-10 (reason assembly + output formatting)");

        ResultWithAgenticScope<String> result = config.getTailSequence()
                .invokeWithAgenticScope(Map.of(
                        "enrichedCandidates", enrichedCandidates,
                        "fileNames", fileNames));

        String finalOutput = result.result();
        if (finalOutput == null || finalOutput.isBlank()) {
            // Fallback: try reading from scope
            finalOutput = readScopeString(result.agenticScope(), "finalOutput");
        }

        log.info("TAIL phase complete — finalOutput ready");
        return finalOutput;
    }

    // =========================================================================
    //  CRITIC Loop — manual invocation in chunked path
    // =========================================================================

    /**
     * Runs critic → optional refiner loop manually.
     *
     * <p>In the direct path, the loop is built by agentFactory.loop() and
     * runs inside the sequence. In the chunked path, we run it manually
     * because there is no shared scope across phases.</p>
     */
    private String executeCriticLoop(String finalOutput,
                                     String sourceText,
                                     String enrichedCandidates) {
        log.info("CRITIC phase — first critic + refiner loop (max {} iterations)",
                CsmExtractionWorkflowConfigV6.REFINEMENT_LOOP_MAX_ITERATIONS);

        UntypedAgent critic = config.getCritic();
        UntypedAgent refiner = config.getRefiner();

        // ── First critic ────────────────────────────────────────────────
        Object review = critic.invoke(Map.of(
                "finalOutput", finalOutput,
                "sourceText", sourceText));

        double score = CsmExtractionWorkflowConfigV6.parseExtractionScoreFromJson(
                objectMapper, review);
        log.info("First critic score: {}", score);

        if (score >= CsmExtractionWorkflowConfigV6.EXTRACTION_QUALITY_THRESHOLD) {
            log.info("Score >= {} — no refinement needed",
                    CsmExtractionWorkflowConfigV6.EXTRACTION_QUALITY_THRESHOLD);
            return finalOutput;
        }

        // ── Refiner loop ────────────────────────────────────────────────
        for (int i = 1; i <= CsmExtractionWorkflowConfigV6.REFINEMENT_LOOP_MAX_ITERATIONS; i++) {
            log.info("Refinement iteration {}/{}",
                    i, CsmExtractionWorkflowConfigV6.REFINEMENT_LOOP_MAX_ITERATIONS);

            // Refiner: fixes critic-identified issues
            Object refined = refiner.invoke(Map.of(
                    "finalOutput", finalOutput,
                    "extractionReview", review,
                    "enrichedCandidates", enrichedCandidates));
            finalOutput = refined != null ? refined.toString() : finalOutput;

            // Critic: re-evaluates
            review = critic.invoke(Map.of(
                    "finalOutput", finalOutput,
                    "sourceText", sourceText));

            score = CsmExtractionWorkflowConfigV6.parseExtractionScoreFromJson(
                    objectMapper, review);
            log.info("Refinement iteration {} — score: {}", i, score);

            if (score >= CsmExtractionWorkflowConfigV6.EXTRACTION_QUALITY_THRESHOLD) {
                log.info("Score >= {} — refinement complete",
                        CsmExtractionWorkflowConfigV6.EXTRACTION_QUALITY_THRESHOLD);
                return finalOutput;
            }
        }

        log.warn("Refinement loop exhausted — final score: {}", score);
        return finalOutput;
    }

    // =========================================================================
    //  JSON Builders
    // =========================================================================

    /**
     * Builds the chunkResults JSON that the Chunk Merger Agent expects.
     *
     * <pre>
     * { "chunks": [
     *     { "chunkIndex": 0, "pageStart": 1, "pageEnd": 20, ...,
     *       "rawNames": {...}, "sourceClassification": {...},
     *       "normalizedCandidates": {...} },
     *     ...
     * ] }
     * </pre>
     */
    private String buildChunkResultsJson(List<ChunkOutput> chunkOutputs) {
        StringBuilder sb = new StringBuilder("{\"chunks\":[");

        for (int i = 0; i < chunkOutputs.size(); i++) {
            if (i > 0) sb.append(',');
            ChunkOutput co = chunkOutputs.get(i);
            ChunkContext chunk = co.chunk();

            sb.append('{');
            sb.append("\"chunkIndex\":").append(chunk.chunkIndex()).append(',');
            sb.append("\"pageStart\":").append(chunk.pageStart()).append(',');
            sb.append("\"pageEnd\":").append(chunk.pageEnd()).append(',');
            sb.append("\"overlapStartPage\":").append(chunk.overlapStartPage()).append(',');
            sb.append("\"overlapEndPage\":").append(chunk.overlapEndPage()).append(',');
            sb.append("\"isFirstChunk\":").append(chunk.isFirstChunk()).append(',');
            sb.append("\"isLastChunk\":").append(chunk.isLastChunk()).append(',');
            sb.append("\"rawNames\":").append(ensureJson(co.rawNames())).append(',');
            sb.append("\"sourceClassification\":").append(ensureJson(co.sourceClassification())).append(',');
            sb.append("\"normalizedCandidates\":").append(ensureJson(co.normalizedCandidates()));
            sb.append('}');
        }

        sb.append("]}");
        return sb.toString();
    }

    // =========================================================================
    //  Internal Helpers
    // =========================================================================

    private String readScopeString(AgenticScope scope, String key) {
        if (scope == null) return "";
        Object value = scope.readState(key);
        return value != null ? value.toString() : "";
    }

    private String ensureJson(String value) {
        if (value == null || value.isBlank()) return "{}";
        String trimmed = value.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed;
        try {
            return objectMapper.writeValueAsString(trimmed);
        } catch (Exception e) {
            return "{}";
        }
    }

    // =========================================================================
    //  Inner Records
    // =========================================================================

    /** Per-chunk MAP output — agent results collected from scope. */
    record ChunkOutput(
            ChunkContext chunk,
            String rawNames,
            String sourceClassification,
            String normalizedCandidates) {}

    /** BRIDGE output — translated format for REDUCE agents. */
    record BridgeOutput(
            String normalizedCandidates,
            String sourceClassification) {}
}
