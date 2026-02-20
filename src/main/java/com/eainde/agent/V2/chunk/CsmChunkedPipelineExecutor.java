package com.eainde.agent.V2.chunk;

import com.db.clm.kyc.ai.chunking.CandidateBatcher;
import com.db.clm.kyc.ai.chunking.ChunkContext;
import com.db.clm.kyc.ai.chunking.DocumentChunker;
import com.db.clm.kyc.ai.config.AgentFactory;
import com.db.clm.kyc.ai.config.AgentSpec;
import com.db.clm.kyc.ai.prompt.AgentNames;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agentic.AgenticScope;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.observability.AgentMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates CSM extraction with automatic chunking for large documents.
 *
 * <h3>Decision Logic:</h3>
 * <pre>
 * execute(sourceText, fileNames)
 *   │
 *   ├── needsChunking(sourceText)?
 *   │     │
 *   │     ├── NO → delegate to V5 pipeline (7 agents, unchanged)
 *   │     │
 *   │     └── YES → Map-Reduce path:
 *   │           1. Chunk document into overlapping page ranges
 *   │           2. MAP: per chunk → [sourceClassifier → personExtractor]
 *   │           3. MERGE: [chunkMerger] → deduped candidates
 *   │           4. needsBatching(mergedCandidates)?
 *   │               ├── NO → [classifier → scorer] on full set
 *   │               └── YES → batch through [classifier → scorer], merge
 *   │           5. [outputAssembler → firstCritic → refinementLoop]
 *   │           6. Return finalOutput
 *   │
 *   └── Return finalOutput
 * </pre>
 *
 * <h3>Configuration (application.yml):</h3>
 * <pre>
 * csm:
 *   chunking:
 *     enabled: true
 *     max-token-estimate: 100000    # 80% of model context window
 *     pages-per-chunk: 20
 *     overlap-pages: 5
 *     page-delimiter: "\\f"
 *   batching:
 *     enabled: true
 *     batch-size: 50
 * </pre>
 */
@Component
public class CsmChunkedPipelineExecutor {

    private static final Logger log = LoggerFactory.getLogger(CsmChunkedPipelineExecutor.class);

    private final AgentFactory agentFactory;
    private final ObjectMapper objectMapper;
    private final CsmExtractionSubWorkflowConfigV5 v5Config;

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

    // ── Pipeline config ─────────────────────────────────────────────────
    private static final int    REFINEMENT_LOOP_MAX_ITERATIONS = 3;
    private static final double EXTRACTION_QUALITY_THRESHOLD   = 0.85;

    private static final AgentMonitor monitor = new AgentMonitor();

    public CsmChunkedPipelineExecutor(AgentFactory agentFactory,
                                      ObjectMapper objectMapper,
                                      CsmExtractionSubWorkflowConfigV5 v5Config) {
        this.agentFactory = agentFactory;
        this.objectMapper = objectMapper;
        this.v5Config = v5Config;
    }

    // =========================================================================
    //  Public API
    // =========================================================================

    /**
     * Executes CSM extraction on a single document, automatically choosing
     * between the direct pipeline and the chunked map-reduce path.
     *
     * @param sourceText the full document text
     * @param fileNames  comma-separated or JSON list of document names
     * @param scope      the AgenticScope for state management
     * @return the final extracted_records JSON string
     */
    public String execute(String sourceText, String fileNames, AgenticScope scope) {
        DocumentChunker chunker = buildChunker();

        if (!chunkingEnabled || !chunker.needsChunking(sourceText, maxTokenEstimate)) {
            log.info("Document fits within token limit — using direct V5 pipeline");
            return executeDirect(sourceText, fileNames, scope);
        }

        log.info("Document exceeds token limit — using chunked map-reduce pipeline");
        return executeChunked(sourceText, fileNames, scope, chunker);
    }

    // =========================================================================
    //  Direct Path (small documents)
    // =========================================================================

    /**
     * Delegates to the standard 7-agent V5 pipeline.
     */
    private String executeDirect(String sourceText, String fileNames, AgenticScope scope) {
        scope.writeState("sourceText", sourceText);
        scope.writeState("fileNames", fileNames);

        UntypedAgent pipeline = v5Config.buildExtractionSubWorkflow();
        pipeline.invoke(scope);

        return scope.readState("finalOutput", "");
    }

    // =========================================================================
    //  Chunked Map-Reduce Path (large documents)
    // =========================================================================

    /**
     * Executes the chunked pipeline:
     * 1. Chunk → 2. Map (agents 1-2) → 3. Merge → 4. Classify/Score → 5. Assemble/Critic
     */
    private String executeChunked(String sourceText, String fileNames,
                                  AgenticScope scope, DocumentChunker chunker) {

        // ── Step 1: Chunk the document ──────────────────────────────────
        List<ChunkContext> chunks = chunker.chunk(sourceText);
        log.info("Document split into {} chunks", chunks.size());

        // ── Step 2: MAP — run agents 1-2 per chunk ─────────────────────
        List<ChunkResult> chunkResults = processChunks(chunks, fileNames);

        // ── Step 3: MERGE — deduplicate across chunks ───────────────────
        String mergedResult = mergeChunks(chunkResults, scope);

        // ── Step 4: REDUCE — classify, score, assemble, critic loop ─────
        return reduceAndAssemble(mergedResult, fileNames, scope);
    }

    /**
     * MAP phase: processes each chunk through source-classifier and person-extractor.
     *
     * <p>Currently sequential. For parallel execution with virtual threads, replace
     * the for-loop with:</p>
     * <pre>
     * try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
     *     List&lt;Future&lt;ChunkResult&gt;&gt; futures = chunks.stream()
     *         .map(chunk -> executor.submit(() -> processOneChunk(chunk, fileNames)))
     *         .toList();
     *     return futures.stream().map(Future::get).toList();
     * }
     * </pre>
     */
    private List<ChunkResult> processChunks(List<ChunkContext> chunks, String fileNames) {
        // Build the extraction mini-pipeline (agents 1-2) once
        AgentSpec sourceClassifierSpec = AgentSpec.of(
                        AgentNames.SOURCE_CLASSIFIER,
                        "Classifies sources within a document chunk")
                .inputs("sourceText", "fileNames")
                .outputKey("sourceClassification")
                .listener(monitor)
                .build();

        AgentSpec personExtractorSpec = AgentSpec.of(
                        AgentNames.PERSON_EXTRACTOR,
                        "Extracts persons from a document chunk")
                .inputs("sourceText", "sourceClassification", "fileNames")
                .outputKey("rawCandidates")
                .listener(monitor)
                .build();

        List<ChunkResult> results = new ArrayList<>();

        for (ChunkContext chunk : chunks) {
            log.info("Processing {}", chunk);

            // Create a fresh scope for each chunk
            // Each chunk gets its own agent instances to avoid state contamination
            UntypedAgent chunkClassifier = agentFactory.create(sourceClassifierSpec);
            UntypedAgent chunkExtractor = agentFactory.create(personExtractorSpec);

            UntypedAgent chunkPipeline = agentFactory.sequence(
                    "rawCandidates", chunkClassifier, chunkExtractor);

            // Create chunk-local scope and seed inputs
            AgenticScope chunkScope = AgenticScope.create();
            chunkScope.writeState("sourceText", chunk.chunkText());
            chunkScope.writeState("fileNames", fileNames);

            // Execute agents 1-2 on this chunk
            chunkPipeline.invoke(chunkScope);

            String sourceClassification = chunkScope.readState("sourceClassification", "{}");
            String rawCandidates = chunkScope.readState("rawCandidates", "{}");

            results.add(new ChunkResult(chunk, sourceClassification, rawCandidates));
            log.info("Completed {} — extracted candidates", chunk);
        }

        return results;
    }

    /**
     * MERGE phase: aggregates chunk results and runs the chunk-merger agent.
     */
    private String mergeChunks(List<ChunkResult> chunkResults, AgenticScope scope) {
        // Build the merger input JSON
        String chunkResultsJson = buildChunkResultsJson(chunkResults);

        // Create and run the chunk-merger agent
        AgentSpec chunkMergerSpec = AgentSpec.of(
                        AgentNames.CHUNK_MERGER,
                        "Deduplicates persons and merges source classifications across chunks")
                .inputs("chunkResults")
                .outputKey("mergedResult")
                .listener(monitor)
                .build();

        UntypedAgent chunkMerger = agentFactory.create(chunkMergerSpec);

        AgenticScope mergerScope = AgenticScope.create();
        mergerScope.writeState("chunkResults", chunkResultsJson);

        chunkMerger.invoke(mergerScope);

        String mergedResult = mergerScope.readState("mergedResult", "{}");

        log.info("Chunk merger completed — candidates deduplicated");
        return mergedResult;
    }

    /**
     * REDUCE phase: runs agents 3-7 on the merged result, with optional batching.
     */
    private String reduceAndAssemble(String mergedResult, String fileNames, AgenticScope scope) {

        // Parse merged result to get candidates and source classification
        String rawCandidates;
        String sourceClassification;
        try {
            var root = objectMapper.readTree(mergedResult);
            rawCandidates = objectMapper.writeValueAsString(
                    root.has("merged_candidates") ? root.get("merged_candidates") : root);
            sourceClassification = objectMapper.writeValueAsString(
                    root.has("global_source_classification")
                            ? root.get("global_source_classification") : objectMapper.createObjectNode());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse merged result", e);
        }

        // ── Classify + Score (with optional batching) ───────────────────
        String scoredCandidates = classifyAndScore(rawCandidates, sourceClassification, scope);

        // ── Output Assembly + Critic/Refiner Loop ───────────────────────
        return assembleAndRefine(scoredCandidates, fileNames, scope);
    }

    /**
     * Runs agents 3-4 (classifier + scorer), with batching if needed.
     */
    private String classifyAndScore(String rawCandidates, String sourceClassification,
                                    AgenticScope scope) {

        CandidateBatcher batcher = new CandidateBatcher(objectMapper, batchSize);

        // Wrap rawCandidates in expected format
        String wrappedCandidates;
        try {
            ObjectNode wrapper = objectMapper.createObjectNode();
            wrapper.set("raw_candidates", objectMapper.readTree(rawCandidates));
            wrappedCandidates = objectMapper.writeValueAsString(wrapper);
        } catch (JsonProcessingException e) {
            wrappedCandidates = rawCandidates;
        }

        if (!batchingEnabled || !batcher.needsBatching(wrappedCandidates, "raw_candidates")) {
            // ── No batching: run agents 3-4 on full set ─────────────────
            log.info("Candidates fit in single pass — running classifier + scorer directly");
            return runClassifierScorer(wrappedCandidates, sourceClassification);
        }

        // ── Batch: split, process each batch, merge ─────────────────────
        log.info("Candidates exceed batch threshold — batching through classifier + scorer");
        List<String> batches = batcher.split(wrappedCandidates, "raw_candidates");
        List<String> batchResults = new ArrayList<>();

        for (int i = 0; i < batches.size(); i++) {
            log.info("Processing batch {}/{}", i + 1, batches.size());
            String batchResult = runClassifierScorer(batches.get(i), sourceClassification);
            batchResults.add(batchResult);
        }

        return batcher.mergeResults(batchResults, "scored_candidates");
    }

    /**
     * Runs classifier (agent 3) → scorer (agent 4) on a candidates set.
     */
    private String runClassifierScorer(String rawCandidatesJson, String sourceClassification) {

        AgentSpec classifierSpec = AgentSpec.of(
                        AgentNames.CSM_CLASSIFIER,
                        "Classifies candidates for CSM eligibility")
                .inputs("rawCandidates", "sourceText", "sourceClassification")
                .outputKey("classifiedCandidates")
                .listener(monitor)
                .build();

        AgentSpec scorerSpec = AgentSpec.of(
                        AgentNames.SCORER,
                        "Computes scores and validates quality gates")
                .inputs("classifiedCandidates")
                .outputKey("scoredCandidates")
                .listener(monitor)
                .build();

        UntypedAgent classifier = agentFactory.create(classifierSpec);
        UntypedAgent scorer = agentFactory.create(scorerSpec);

        UntypedAgent classifyScore = agentFactory.sequence(
                "scoredCandidates", classifier, scorer);

        AgenticScope batchScope = AgenticScope.create();
        batchScope.writeState("rawCandidates", rawCandidatesJson);
        batchScope.writeState("sourceClassification", sourceClassification);
        // For batched processing, sourceText is not re-sent to avoid exceeding limits.
        // The classifier works on structured JSON candidates — it doesn't need raw text
        // for batch re-processing since governance evidence was already captured by
        // the person extractor in the MAP phase.
        batchScope.writeState("sourceText", "[See source classification for document references]");

        classifyScore.invoke(batchScope);

        return batchScope.readState("scoredCandidates", "{}");
    }

    /**
     * Runs agents 5-7 (output assembler + critic/refiner loop).
     */
    private String assembleAndRefine(String scoredCandidates, String fileNames,
                                     AgenticScope scope) {

        // ── Agent 5: Output Assembler ───────────────────────────────────
        AgentSpec assemblerSpec = AgentSpec.of(
                        AgentNames.OUTPUT_ASSEMBLER,
                        "Assembles final JSON with reason strings")
                .inputs("scoredCandidates", "fileNames")
                .outputKey("finalOutput")
                .listener(monitor)
                .build();

        // ── Agent 6a: First Critic (before loop) ────────────────────────
        AgentSpec firstCriticSpec = AgentSpec.of(
                        AgentNames.EXTRACTION_CRITIC,
                        "Reviews output against compliance checklist")
                .inputs("finalOutput", "sourceText")
                .outputKey("extractionReview")
                .listener(monitor)
                .build();

        // ── Agent 7: Output Refiner (inside loop) ───────────────────────
        AgentSpec refinerSpec = AgentSpec.of(
                        AgentNames.OUTPUT_REFINER,
                        "Fixes critic-identified issues")
                .inputs("finalOutput", "extractionReview", "scoredCandidates")
                .outputKey("finalOutput")
                .listener(monitor)
                .build();

        // ── Agent 6b: Loop Critic (inside loop) ─────────────────────────
        AgentSpec loopCriticSpec = AgentSpec.of(
                        AgentNames.EXTRACTION_CRITIC,
                        "Re-evaluates refined output")
                .inputs("finalOutput", "sourceText")
                .outputKey("extractionReview")
                .listener(monitor)
                .build();

        UntypedAgent assembler = agentFactory.create(assemblerSpec);
        UntypedAgent firstCritic = agentFactory.create(firstCriticSpec);

        UntypedAgent refinementLoop = agentFactory.loop(
                REFINEMENT_LOOP_MAX_ITERATIONS,
                s -> CsmExtractionSubWorkflowConfigV5.parseExtractionScore(
                        s.readState("extractionReview", "NO"))
                        >= EXTRACTION_QUALITY_THRESHOLD,
                refinerSpec, loopCriticSpec
        );

        UntypedAgent assemblePipeline = agentFactory.sequence(
                "finalOutput", assembler, firstCritic, refinementLoop);

        // Seed the scope with required inputs
        scope.writeState("scoredCandidates", scoredCandidates);
        scope.writeState("fileNames", fileNames);
        // sourceText is already in scope from the caller (or a placeholder for chunked path)

        assemblePipeline.invoke(scope);

        return scope.readState("finalOutput", "{}");
    }

    // =========================================================================
    //  Internal Helpers
    // =========================================================================

    /**
     * Builds the JSON input for the chunk-merger agent from per-chunk results.
     *
     * <pre>
     * {
     *   "chunks": [
     *     {
     *       "chunkIndex": 0,
     *       "pageStart": 1,
     *       "pageEnd": 20,
     *       "overlapStartPage": -1,
     *       "overlapEndPage": -1,
     *       "rawCandidates": [...],
     *       "sourceClassification": [...]
     *     },
     *     ...
     *   ]
     * }
     * </pre>
     */
    private String buildChunkResultsJson(List<ChunkResult> chunkResults) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode chunksArray = objectMapper.createArrayNode();

            for (ChunkResult cr : chunkResults) {
                ObjectNode chunkNode = objectMapper.createObjectNode();
                chunkNode.put("chunkIndex", cr.chunk().chunkIndex());
                chunkNode.put("pageStart", cr.chunk().pageStart());
                chunkNode.put("pageEnd", cr.chunk().pageEnd());
                chunkNode.put("overlapStartPage", cr.chunk().overlapStartPage());
                chunkNode.put("overlapEndPage", cr.chunk().overlapEndPage());
                chunkNode.put("isFirstChunk", cr.chunk().isFirstChunk());
                chunkNode.put("isLastChunk", cr.chunk().isLastChunk());

                // Parse JSON strings back to nodes for proper nesting
                chunkNode.set("rawCandidates", objectMapper.readTree(cr.rawCandidates()));
                chunkNode.set("sourceClassification", objectMapper.readTree(cr.sourceClassification()));

                chunksArray.add(chunkNode);
            }

            root.set("chunks", chunksArray);
            return objectMapper.writeValueAsString(root);

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to build chunk results JSON", e);
        }
    }

    private DocumentChunker buildChunker() {
        return DocumentChunker.builder()
                .pagesPerChunk(pagesPerChunk)
                .overlapPages(overlapPages)
                .pageDelimiter(pageDelimiterRegex)
                .build();
    }

    // =========================================================================
    //  Internal Records
    // =========================================================================

    /**
     * Holds the output of agents 1-2 for a single chunk.
     */
    record ChunkResult(
            ChunkContext chunk,
            String sourceClassification,
            String rawCandidates
    ) {}
}
