package com.eainde.agent.mapper_reducer;

import com.db.clm.kyc.ai.config.AgentFactory;
import com.db.clm.kyc.ai.config.AgentSpec;
import com.db.clm.kyc.ai.model.ExtractionReview;
import com.db.clm.kyc.ai.prompt.AgentNames;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.observability.AgentMonitor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * CSM Extraction Pipeline V6 — 12-agent wave-based decomposition with Map-Reduce.
 *
 * <h3>Two execution paths, same reduce tail:</h3>
 * <pre>
 * DIRECT (small doc):
 *   Agent 1→2→3→4→5→6→7→8→Wave5Merge→9→10→11→Loop(12→11)
 *
 * CHUNKED (large doc):
 *   MapPhase(per-chunk 1→2→3) → ChunkMerger → Bridge
 *     → BatchingReduce(4→5→6→7→8→Wave5Merge) → 9→10→11→Loop(12→11)
 * </pre>
 *
 * <h3>Design principles:</h3>
 * <ul>
 *   <li>Agent ↔ Agent: JSON strings via AgenticScope</li>
 *   <li>Orchestration logic: DTOs at merge/batch/loop boundaries only</li>
 *   <li>Every component is an UntypedAgent — framework controls execution</li>
 *   <li>AgenticScope is NEVER created — always provided by the framework</li>
 * </ul>
 */
@Log4j2
@Component
public class CsmExtractionWorkflowConfigV6 {

    static final int    REFINEMENT_LOOP_MAX_ITERATIONS = 3;
    static final double EXTRACTION_QUALITY_THRESHOLD   = 0.85;

    private final AgentFactory agentFactory;
    private final ObjectMapper objectMapper;

    private static final AgentMonitor monitor = new AgentMonitor();

    // ── Chunking config ─────────────────────────────────────────────────
    @Value("${csm.chunking.enabled:true}")
    private boolean chunkingEnabled;

    @Value("${csm.chunking.max-token-estimate:100000}")
    private int maxTokenEstimate;

    @Value("${csm.chunking.pages-per-chunk:20}")
    private int pagesPerChunk;

    @Value("${csm.chunking.overlap-pages:5}")
    private int overlapPages;

    @Value("${csm.chunking.page-delimiter:\\f}")
    private String pageDelimiterRegex;

    // ── Batching config ─────────────────────────────────────────────────
    @Value("${csm.batching.enabled:true}")
    private boolean batchingEnabled;

    @Value("${csm.batching.batch-size:50}")
    private int batchSize;

    public CsmExtractionWorkflowConfigV6(AgentFactory agentFactory,
                                         ObjectMapper objectMapper) {
        this.agentFactory = agentFactory;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    //  Agent Specs — all String-based I/O
    // =========================================================================

    // ── Wave 1 (MAP agents — also used in direct path) ──────────────────

    static final AgentSpec CANDIDATE_EXTRACTOR_SPEC = AgentSpec
            .of(AgentNames.CANDIDATE_EXTRACTOR,
                    "Extracts all person names from raw document text")
            .inputs("sourceText", "fileNames")
            .outputKey("rawNames")
            .listener(monitor)
            .build();

    static final AgentSpec SOURCE_CLASSIFIER_SPEC = AgentSpec
            .of(AgentNames.SOURCE_CLASSIFIER,
                    "Ranks and classifies sources using IDandV hierarchy")
            .inputs("sourceText", "fileNames")
            .outputKey("sourceClassification")
            .listener(monitor)
            .build();

    // ── Wave 2 (MAP agent — also used in direct path) ───────────────────

    static final AgentSpec NAME_NORMALIZER_SPEC = AgentSpec
            .of(AgentNames.NAME_NORMALIZER,
                    "Normalizes names — romanization, OCR healing, aliases")
            .inputs("rawNames", "sourceClassification")
            .outputKey("normalizedCandidates")
            .listener(monitor)
            .build();

    // ── Chunk Merger (chunked path only) ────────────────────────────────

    static final AgentSpec CHUNK_MERGER_SPEC = AgentSpec
            .of(AgentNames.CHUNK_MERGER,
                    "Deduplicates persons and merges sources across chunks")
            .inputs("chunkResults")
            .outputKey("mergedResult")
            .listener(monitor)
            .build();

    // ── Wave 3 (REDUCE start) ───────────────────────────────────────────

    static final AgentSpec DEDUP_LINKER_SPEC = AgentSpec
            .of(AgentNames.DEDUP_LINKER,
                    "Deduplicates persons and links to prevailing source")
            .inputs("normalizedCandidates", "sourceClassification")
            .outputKey("dedupedCandidates")
            .listener(monitor)
            .build();

    // ── Wave 4 ──────────────────────────────────────────────────────────

    static final AgentSpec CSM_CLASSIFIER_SPEC = AgentSpec
            .of(AgentNames.CSM_CLASSIFIER,
                    "Classifies each candidate for CSM eligibility — universal governance rules")
            .inputs("dedupedCandidates", "sourceText", "sourceClassification")
            .outputKey("classifiedCandidates")
            .listener(monitor)
            .build();

    // ── Wave 5 (parallel in concept, sequential in execution) ───────────

    static final AgentSpec COUNTRY_OVERRIDE_SPEC = AgentSpec
            .of(AgentNames.COUNTRY_OVERRIDE,
                    "Applies country-specific profile overrides to classified candidates")
            .inputs("classifiedCandidates", "sourceClassification")
            .outputKey("countryOverrides")
            .listener(monitor)
            .build();

    static final AgentSpec TITLE_EXTRACTOR_SPEC = AgentSpec
            .of(AgentNames.TITLE_EXTRACTOR,
                    "Extracts jobTitle and personalTitle with ANCHOR GATE")
            .inputs("classifiedCandidates", "sourceText")
            .outputKey("titleExtractions")
            .listener(monitor)
            .build();

    static final AgentSpec SCORING_ENGINE_SPEC = AgentSpec
            .of(AgentNames.SCORING_ENGINE,
                    "Computes explanatory D-scores and validates quality gates")
            .inputs("classifiedCandidates")
            .outputKey("scoredCandidates")
            .listener(monitor)
            .build();

    // ── Wave 6 ──────────────────────────────────────────────────────────

    static final AgentSpec REASON_ASSEMBLER_SPEC = AgentSpec
            .of(AgentNames.REASON_ASSEMBLER,
                    "Assembles canonical reason strings in R2 order")
            .inputs("enrichedCandidates")
            .outputKey("reasonedCandidates")
            .listener(monitor)
            .build();

    // ── Wave 7 ──────────────────────────────────────────────────────────

    static final AgentSpec OUTPUT_FORMATTER_SPEC = AgentSpec
            .of(AgentNames.OUTPUT_FORMATTER,
                    "Formats final JSON output and validates schema")
            .inputs("reasonedCandidates", "fileNames")
            .outputKey("finalOutput")
            .listener(monitor)
            .build();

    // ── Wave 8 + Loop ───────────────────────────────────────────────────

    static final AgentSpec FIRST_CRITIC_SPEC = AgentSpec
            .of(AgentNames.EXTRACTION_CRITIC,
                    "Reviews output against compliance checklist")
            .inputs("finalOutput", "sourceText")
            .outputKey("extractionReview")
            .listener(monitor)
            .build();

    static final AgentSpec OUTPUT_REFINER_SPEC = AgentSpec
            .of(AgentNames.OUTPUT_REFINER,
                    "Fixes critic-identified issues in the final output")
            .inputs("finalOutput", "extractionReview", "enrichedCandidates")
            .outputKey("finalOutput")
            .listener(monitor)
            .build();

    static final AgentSpec LOOP_CRITIC_SPEC = AgentSpec
            .of(AgentNames.EXTRACTION_CRITIC,
                    "Re-evaluates refined output against compliance checklist")
            .inputs("finalOutput", "sourceText")
            .outputKey("extractionReview")
            .listener(monitor)
            .build();

    // =========================================================================
    //  Workflow Builders
    // =========================================================================

    /**
     * Builds the DIRECT path — all 12 agents in sequence.
     * Used when document fits within token limit.
     *
     * <pre>
     * Agent 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → Wave5Merge → 9 → 10 → 11 → Loop(12→11)
     * </pre>
     */
    public UntypedAgent buildDirectWorkflow() {
        log.info("Building CSM extraction V6 — DIRECT path (12 agents sequential)");

        Wave5MergerAgent wave5Merger = new Wave5MergerAgent(objectMapper);

        UntypedAgent refinementLoop = agentFactory.loop(
                REFINEMENT_LOOP_MAX_ITERATIONS,
                scope -> parseExtractionScore(scope) >= EXTRACTION_QUALITY_THRESHOLD,
                OUTPUT_REFINER_SPEC, LOOP_CRITIC_SPEC);

        return agentFactory.sequence("finalOutput",
                agentFactory.create(CANDIDATE_EXTRACTOR_SPEC),   // Wave 1a → rawNames
                agentFactory.create(SOURCE_CLASSIFIER_SPEC),     // Wave 1b → sourceClassification
                agentFactory.create(NAME_NORMALIZER_SPEC),       // Wave 2  → normalizedCandidates
                agentFactory.create(DEDUP_LINKER_SPEC),          // Wave 3  → dedupedCandidates
                agentFactory.create(CSM_CLASSIFIER_SPEC),        // Wave 4  → classifiedCandidates
                agentFactory.create(COUNTRY_OVERRIDE_SPEC),      // Wave 5a → countryOverrides
                agentFactory.create(TITLE_EXTRACTOR_SPEC),       // Wave 5b → titleExtractions
                agentFactory.create(SCORING_ENGINE_SPEC),        // Wave 5c → scoredCandidates
                wave5Merger,                                      // Wave 5 merge → enrichedCandidates
                agentFactory.create(REASON_ASSEMBLER_SPEC),      // Wave 6  → reasonedCandidates
                agentFactory.create(OUTPUT_FORMATTER_SPEC),      // Wave 7  → finalOutput
                agentFactory.create(FIRST_CRITIC_SPEC),          // Wave 8  → extractionReview
                refinementLoop                                    // Loop    → finalOutput (corrected)
        );
    }

    /**
     * Builds the CHUNKED path — Map-Reduce with optional batching.
     * Used when document exceeds token limit.
     *
     * <pre>
     * MAP(per-chunk 1→2→3) → ChunkMerger → Bridge
     *   → BatchingReduce(4→5→6→7→8→Wave5Merge)
     *   → 9 → 10 → 11 → Loop(12→11)
     * </pre>
     */
    public UntypedAgent buildChunkedWorkflow() {
        log.info("Building CSM extraction V6 — CHUNKED path (map-reduce)");

        // MAP phase: per-chunk agents 1-3
        MapPhaseAgent mapPhaseAgent = new MapPhaseAgent(
                agentFactory, objectMapper,
                pagesPerChunk, overlapPages, pageDelimiterRegex);

        // MERGE phase: LLM chunk merger
        UntypedAgent chunkMerger = agentFactory.create(CHUNK_MERGER_SPEC);

        // Bridge: mergedResult → normalizedCandidates + sourceClassification
        ChunkToReduceBridgeAgent bridgeAgent = new ChunkToReduceBridgeAgent(objectMapper);

        // REDUCE phase: agents 4-8 + Wave5Merge with optional batching
        BatchingReduceAgent batchingReduceAgent = new BatchingReduceAgent(
                agentFactory, objectMapper,
                batchingEnabled, batchSize);

        // Post-batching tail: agents 9-12 (always on full set)
        Wave5MergerAgent wave5MergerForDirect = new Wave5MergerAgent(objectMapper);

        UntypedAgent refinementLoop = agentFactory.loop(
                REFINEMENT_LOOP_MAX_ITERATIONS,
                scope -> parseExtractionScore(scope) >= EXTRACTION_QUALITY_THRESHOLD,
                OUTPUT_REFINER_SPEC, LOOP_CRITIC_SPEC);

        return agentFactory.sequence("finalOutput",
                mapPhaseAgent,                                    // MAP → chunkResults
                chunkMerger,                                      // MERGE → mergedResult
                bridgeAgent,                                      // Bridge → normalizedCandidates
                batchingReduceAgent,                              // REDUCE 4-8 → enrichedCandidates
                agentFactory.create(REASON_ASSEMBLER_SPEC),      // Wave 6  → reasonedCandidates
                agentFactory.create(OUTPUT_FORMATTER_SPEC),      // Wave 7  → finalOutput
                agentFactory.create(FIRST_CRITIC_SPEC),          // Wave 8  → extractionReview
                refinementLoop                                    // Loop    → finalOutput (corrected)
        );
    }

    // =========================================================================
    //  Shared Helpers
    // =========================================================================

    /**
     * Reads extraction_score from scope. String → DTO → double.
     * Used as loop exit condition in both direct and chunked paths.
     */
    double parseExtractionScore(AgenticScope scope) {
        Object raw = scope.readState("extractionReview");
        if (raw == null) return 0.0;
        String reviewJson = raw.toString();
        if (reviewJson.isBlank()) return 0.0;

        // Primary: deserialize to DTO
        try {
            ExtractionReview review = objectMapper.readValue(reviewJson, ExtractionReview.class);
            return review.extractionScore();
        } catch (Exception e) {
            log.debug("Failed to deserialize ExtractionReview, falling back to manual parse", e);
        }

        // Fallback: manual JSON parse
        try {
            String key = "\"extraction_score\"";
            int idx = reviewJson.indexOf(key);
            if (idx == -1) return 0.0;
            int colonIdx = reviewJson.indexOf(':', idx + key.length());
            if (colonIdx == -1) return 0.0;
            StringBuilder sb = new StringBuilder();
            for (int i = colonIdx + 1; i < reviewJson.length(); i++) {
                char c = reviewJson.charAt(i);
                if (Character.isDigit(c) || c == '.') sb.append(c);
                else if (!Character.isWhitespace(c) && sb.length() > 0) break;
            }
            return sb.length() > 0 ? Double.parseDouble(sb.toString()) : 0.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    // =========================================================================
    //  Accessors for RoutingExtractionAgent
    // =========================================================================

    boolean isChunkingEnabled()  { return chunkingEnabled; }
    int getMaxTokenEstimate()    { return maxTokenEstimate; }
}
