package com.eainde.agent.mapper_reducer_u;

package com.db.clm.kyc.ai.agents;

import com.db.clm.kyc.ai.config.AgentFactory;
import com.db.clm.kyc.ai.config.AgentSpec;
import com.db.clm.kyc.ai.model.ExtractionReview;
import com.db.clm.kyc.ai.prompt.AgentNames;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.observability.AgentMonitor;
import jakarta.annotation.PostConstruct;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * CSM Extraction Pipeline V6 — 12-agent wave-based decomposition with Map-Reduce.
 *
 * <p>Responsible for:</p>
 * <ul>
 *   <li>Declaring all 14 AgentSpecs (12 pipeline + chunk merger + consolidation)</li>
 *   <li>Creating all UntypedAgent instances from specs</li>
 *   <li>Building reusable sub-workflows (mapSequence, reduceSequence, tailSequence)</li>
 *   <li>Building the full direct workflow (single sequence + loop)</li>
 * </ul>
 *
 * <h3>Direct path (small doc) — one sequence, framework manages scope:</h3>
 * <pre>
 * Agent 1→2→3→4→5→6→7→8→Wave5Merge→9→10→11→Loop(12→11)
 * </pre>
 *
 * <h3>Chunked path (large doc) — orchestrated by CsmPipelineOrchestrator:</h3>
 * <pre>
 * MAP: mapSequence per chunk → chunkMerger → bridge (Java)
 * REDUCE: reduceSequence per batch → merge batches → tailSequence → criticLoop
 * </pre>
 */
@Log4j2
@Component
public class CsmExtractionWorkflowConfigV6 {

    static final int    REFINEMENT_LOOP_MAX_ITERATIONS = 3;
    static final double EXTRACTION_QUALITY_THRESHOLD   = 0.85;

    private final AgentFactory agentFactory;
    private final ObjectMapper objectMapper;

    private static final AgentMonitor monitor = new AgentMonitor();

    // ── Configuration ───────────────────────────────────────────────────

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

    @Value("${csm.batching.enabled:true}")
    private boolean batchingEnabled;

    @Value("${csm.batching.batch-size:50}")
    private int batchSize;

    // ── Agent instances (created at startup) ────────────────────────────

    private UntypedAgent candidateExtractor;
    private UntypedAgent sourceClassifier;
    private UntypedAgent nameNormalizer;
    private UntypedAgent dedupLinker;
    private UntypedAgent csmClassifier;
    private UntypedAgent countryOverride;
    private UntypedAgent titleExtractor;
    private UntypedAgent scoringEngine;
    private UntypedAgent reasonAssembler;
    private UntypedAgent outputFormatter;
    private UntypedAgent firstCritic;
    private UntypedAgent outputRefiner;
    private UntypedAgent chunkMerger;

    // ── Sub-workflows ───────────────────────────────────────────────────

    /** Agents 1→2→3: per-chunk extraction. Final output key: normalizedCandidates */
    private UntypedAgent mapSequence;

    /** Agents 4→5→6→7→8→Wave5Merge: per-batch reduce. Final output key: enrichedCandidates */
    private UntypedAgent reduceSequence;

    /** Agents 9→10: post-reduce tail. Final output key: finalOutput */
    private UntypedAgent tailSequence;

    /** Full direct workflow: all 12 agents + critic loop. Final output key: finalOutput */
    private UntypedAgent directWorkflow;

    // =========================================================================
    //  Agent Specs — all String-based I/O via AgenticScope
    // =========================================================================

    // ── MAP agents (Wave 1-2) ───────────────────────────────────────────

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

    // ── REDUCE agents (Wave 3-5) ────────────────────────────────────────

    static final AgentSpec DEDUP_LINKER_SPEC = AgentSpec
            .of(AgentNames.DEDUP_LINKER,
                    "Deduplicates persons and links to prevailing source")
            .inputs("normalizedCandidates", "sourceClassification")
            .outputKey("dedupedCandidates")
            .listener(monitor)
            .build();

    static final AgentSpec CSM_CLASSIFIER_SPEC = AgentSpec
            .of(AgentNames.CSM_CLASSIFIER,
                    "Classifies each candidate for CSM eligibility — universal governance rules")
            .inputs("dedupedCandidates", "sourceText", "sourceClassification")
            .outputKey("classifiedCandidates")
            .listener(monitor)
            .build();

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

    // ── TAIL agents (Wave 6-7) ──────────────────────────────────────────

    static final AgentSpec REASON_ASSEMBLER_SPEC = AgentSpec
            .of(AgentNames.REASON_ASSEMBLER,
                    "Assembles canonical reason strings in R2 order")
            .inputs("enrichedCandidates")
            .outputKey("reasonedCandidates")
            .listener(monitor)
            .build();

    static final AgentSpec OUTPUT_FORMATTER_SPEC = AgentSpec
            .of(AgentNames.OUTPUT_FORMATTER,
                    "Formats final JSON output and validates schema")
            .inputs("reasonedCandidates", "fileNames")
            .outputKey("finalOutput")
            .listener(monitor)
            .build();

    // ── Critic + Refiner (Wave 8 + Loop) ────────────────────────────────

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
    //  Constructor
    // =========================================================================

    public CsmExtractionWorkflowConfigV6(AgentFactory agentFactory,
                                         ObjectMapper objectMapper) {
        this.agentFactory = agentFactory;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    //  Initialization — create agents and build workflows
    // =========================================================================

    @PostConstruct
    void init() {
        log.info("Initializing CSM extraction V6 — creating agents and workflows");

        // ── Create agent instances from specs ───────────────────────────
        this.candidateExtractor = agentFactory.create(CANDIDATE_EXTRACTOR_SPEC);
        this.sourceClassifier   = agentFactory.create(SOURCE_CLASSIFIER_SPEC);
        this.nameNormalizer     = agentFactory.create(NAME_NORMALIZER_SPEC);
        this.dedupLinker        = agentFactory.create(DEDUP_LINKER_SPEC);
        this.csmClassifier      = agentFactory.create(CSM_CLASSIFIER_SPEC);
        this.countryOverride    = agentFactory.create(COUNTRY_OVERRIDE_SPEC);
        this.titleExtractor     = agentFactory.create(TITLE_EXTRACTOR_SPEC);
        this.scoringEngine      = agentFactory.create(SCORING_ENGINE_SPEC);
        this.reasonAssembler    = agentFactory.create(REASON_ASSEMBLER_SPEC);
        this.outputFormatter    = agentFactory.create(OUTPUT_FORMATTER_SPEC);
        this.firstCritic        = agentFactory.create(FIRST_CRITIC_SPEC);
        this.outputRefiner      = agentFactory.create(OUTPUT_REFINER_SPEC);
        this.chunkMerger        = agentFactory.create(CHUNK_MERGER_SPEC);

        // ── Wave5 Merger (Java logic, not LLM) ─────────────────────────
        Wave5MergerAgent wave5Merger = new Wave5MergerAgent(objectMapper);

        // ── Build sub-workflows ─────────────────────────────────────────

        // MAP: Agents 1→2→3 — used per-chunk in chunked path
        this.mapSequence = agentFactory.sequence("normalizedCandidates",
                candidateExtractor, sourceClassifier, nameNormalizer);

        // REDUCE: Agents 4→5→6→7→8→Wave5Merge — used per-batch or single pass
        this.reduceSequence = agentFactory.sequence("enrichedCandidates",
                dedupLinker, csmClassifier, countryOverride, titleExtractor,
                scoringEngine, wave5Merger);

        // TAIL: Agents 9→10 — always on full set
        this.tailSequence = agentFactory.sequence("finalOutput",
                reasonAssembler, outputFormatter);

        // ── Critic-Refiner Loop ─────────────────────────────────────────
        UntypedAgent refinementLoop = agentFactory.loop(
                REFINEMENT_LOOP_MAX_ITERATIONS,
                scope -> parseExtractionScore(scope) >= EXTRACTION_QUALITY_THRESHOLD,
                OUTPUT_REFINER_SPEC, LOOP_CRITIC_SPEC);

        // ── Direct workflow: full 12-agent sequence + loop ──────────────
        this.directWorkflow = agentFactory.sequence("finalOutput",
                candidateExtractor,      // Wave 1a → rawNames
                sourceClassifier,        // Wave 1b → sourceClassification
                nameNormalizer,          // Wave 2  → normalizedCandidates
                dedupLinker,             // Wave 3  → dedupedCandidates
                csmClassifier,           // Wave 4  → classifiedCandidates
                countryOverride,         // Wave 5a → countryOverrides
                titleExtractor,          // Wave 5b → titleExtractions
                scoringEngine,           // Wave 5c → scoredCandidates
                wave5Merger,             // Wave 5  → enrichedCandidates (Java, no LLM)
                reasonAssembler,         // Wave 6  → reasonedCandidates
                outputFormatter,         // Wave 7  → finalOutput
                firstCritic,             // Wave 8  → extractionReview
                refinementLoop           // Loop    → finalOutput (corrected)
        );

        log.info("CSM extraction V6 initialized — direct workflow + sub-workflows ready");
    }

    // =========================================================================
    //  Accessors — used by CsmPipelineOrchestrator for chunked path
    // =========================================================================

    /** Full direct workflow: all 12 agents + loop. Invoke with {sourceText, fileNames}. */
    public UntypedAgent getDirectWorkflow()  { return directWorkflow; }

    /** MAP sub-workflow: agents 1→2→3. Invoke per chunk with {sourceText, fileNames}. */
    UntypedAgent getMapSequence()            { return mapSequence; }

    /** REDUCE sub-workflow: agents 4→5→6→7→8→Wave5Merge. Invoke with {normalizedCandidates, sourceClassification, sourceText}. */
    UntypedAgent getReduceSequence()         { return reduceSequence; }

    /** TAIL sub-workflow: agents 9→10. Invoke with {enrichedCandidates, fileNames}. */
    UntypedAgent getTailSequence()           { return tailSequence; }

    /** Chunk merger agent (LLM). Invoke with {chunkResults}. */
    UntypedAgent getChunkMerger()            { return chunkMerger; }

    /** Critic agent (LLM). For manual loop in chunked path. */
    UntypedAgent getCritic()                 { return firstCritic; }

    /** Refiner agent (LLM). For manual loop in chunked path. */
    UntypedAgent getRefiner()                { return outputRefiner; }

    /** Jackson mapper — shared. */
    ObjectMapper getObjectMapper()           { return objectMapper; }

    // ── Chunking config accessors ───────────────────────────────────────

    boolean isChunkingEnabled()   { return chunkingEnabled; }
    int getMaxTokenEstimate()     { return maxTokenEstimate; }
    int getPagesPerChunk()        { return pagesPerChunk; }
    int getOverlapPages()         { return overlapPages; }
    String getPageDelimiterRegex(){ return pageDelimiterRegex; }
    boolean isBatchingEnabled()   { return batchingEnabled; }
    int getBatchSize()            { return batchSize; }

    // =========================================================================
    //  Shared Helpers
    // =========================================================================

    /**
     * Reads extraction_score from AgenticScope.
     * Used as Predicate&lt;AgenticScope&gt; in the refinement loop.
     * String → DTO → double at the loop boundary.
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

        // Fallback: manual JSON field extraction
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

    /**
     * Parses extraction_score from a JSON string (for manual critic loop in chunked path).
     */
    static double parseExtractionScoreFromJson(ObjectMapper mapper, Object reviewOutput) {
        if (reviewOutput == null) return 0.0;
        String json = reviewOutput.toString();
        if (json.isBlank()) return 0.0;
        try {
            ExtractionReview review = mapper.readValue(json, ExtractionReview.class);
            return review.extractionScore();
        } catch (Exception e) {
            // Fallback: regex-like manual parse
            try {
                String key = "\"extraction_score\"";
                int idx = json.indexOf(key);
                if (idx == -1) return 0.0;
                int colonIdx = json.indexOf(':', idx + key.length());
                if (colonIdx == -1) return 0.0;
                StringBuilder sb = new StringBuilder();
                for (int i = colonIdx + 1; i < json.length(); i++) {
                    char c = json.charAt(i);
                    if (Character.isDigit(c) || c == '.') sb.append(c);
                    else if (!Character.isWhitespace(c) && sb.length() > 0) break;
                }
                return sb.length() > 0 ? Double.parseDouble(sb.toString()) : 0.0;
            } catch (NumberFormatException nfe) {
                return 0.0;
            }
        }
    }
}