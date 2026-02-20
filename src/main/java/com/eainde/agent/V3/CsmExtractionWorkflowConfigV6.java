package com.eainde.agent.V3;

import com.db.clm.kyc.ai.config.AgentFactory;
import com.db.clm.kyc.ai.config.AgentSpec;
import com.db.clm.kyc.ai.model.ExtractionReview;
import com.db.clm.kyc.ai.prompt.AgentNames;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agentic.AgenticScope;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.observability.AgentMonitor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CSM Extraction Pipeline V6 — 12-agent wave-based decomposition.
 *
 * <h3>Design principles:</h3>
 * <ul>
 *   <li><b>Java orchestrator</b> — deterministic wave execution, no LLM routing</li>
 *   <li><b>Agent ↔ Agent:</b> JSON strings via AgenticScope</li>
 *   <li><b>Orchestration logic:</b> DTOs only at merge boundaries and loop exit</li>
 *   <li><b>Parallel waves:</b> Wave 1 (extraction ∥ source) and Wave 5 (country ∥ title ∥ score)</li>
 * </ul>
 *
 * <pre>
 * WAVE EXECUTION:
 *
 *   sourceText + fileNames
 *     │
 *  ═══╪════════════════════════════════════════════════════════════════
 *  Wave 1 (PARALLEL — independent, both read sourceText):
 *     │
 *     ├──→ Agent 1: Candidate Extractor  ──→ "rawNames"
 *     └──→ Agent 2: Source Classifier     ──→ "sourceClassification"
 *     │
 *  ═══╪════════════════════════════════════════════════════════════════
 *  Wave 2 (needs rawNames + sourceClassification):
 *     │
 *     └──→ Agent 3: Name Normalizer       ──→ "normalizedCandidates"
 *     │
 *  ═══╪════════════════════════════════════════════════════════════════
 *  Wave 3:
 *     │
 *     └──→ Agent 4: Dedup + Source Linkage ──→ "dedupedCandidates"
 *     │
 *  ═══╪════════════════════════════════════════════════════════════════
 *  Wave 4:
 *     │
 *     └──→ Agent 5: CSM Classifier         ──→ "classifiedCandidates"
 *     │
 *  ═══╪════════════════════════════════════════════════════════════════
 *  Wave 5 (PARALLEL — all read classifiedCandidates):
 *     │
 *     ├──→ Agent 6: Country Override    ──→ "countryOverrides"
 *     ├──→ Agent 7: Title Extractor     ──→ "titleExtractions"
 *     └──→ Agent 8: Scoring Engine      ──→ "scoredCandidates"
 *     │
 *     └──→ Java: merge Wave 5 outputs   ──→ "enrichedCandidates"
 *     │
 *  ═══╪════════════════════════════════════════════════════════════════
 *  Wave 6:
 *     │
 *     └──→ Agent 9: Reason Assembler     ──→ "reasonedCandidates"
 *     │
 *  ═══╪════════════════════════════════════════════════════════════════
 *  Wave 7:
 *     │
 *     └──→ Agent 10: Output Formatter    ──→ "finalOutput"
 *     │
 *  ═══╪════════════════════════════════════════════════════════════════
 *  Wave 8 + Loop:
 *     │
 *     └──→ Agent 11: First Critic        ──→ "extractionReview"
 *     │
 *     └──→ Loop (max 3, exit when score ≥ 0.85):
 *            Agent 12: Output Refiner    ──→ "finalOutput"
 *            Agent 11: Loop Critic       ──→ "extractionReview"
 *     │
 *  ═══╪════════════════════════════════════════════════════════════════
 *     ▼
 *   finalOutput (JSON string)
 * </pre>
 */
@Log4j2
@Component
public class CsmExtractionWorkflowConfigV6 {

    private static final int    REFINEMENT_LOOP_MAX_ITERATIONS = 3;
    private static final double EXTRACTION_QUALITY_THRESHOLD   = 0.85;

    private final AgentFactory agentFactory;
    private final ObjectMapper objectMapper;

    private static final AgentMonitor monitor = new AgentMonitor();

    public CsmExtractionWorkflowConfigV6(AgentFactory agentFactory,
                                         ObjectMapper objectMapper) {
        this.agentFactory = agentFactory;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    //  Agent Specs — all String-based I/O
    // =========================================================================

    // ── Wave 1 (parallel) ───────────────────────────────────────────────

    private static final AgentSpec CANDIDATE_EXTRACTOR_SPEC = AgentSpec
            .of(AgentNames.CANDIDATE_EXTRACTOR,
                    "Extracts all person names from raw document text")
            .inputs("sourceText", "fileNames")
            .outputKey("rawNames")
            .listener(monitor)
            .build();

    private static final AgentSpec SOURCE_CLASSIFIER_SPEC = AgentSpec
            .of(AgentNames.SOURCE_CLASSIFIER,
                    "Ranks and classifies sources using IDandV hierarchy")
            .inputs("sourceText", "fileNames")
            .outputKey("sourceClassification")
            .listener(monitor)
            .build();

    // ── Wave 2 ──────────────────────────────────────────────────────────

    private static final AgentSpec NAME_NORMALIZER_SPEC = AgentSpec
            .of(AgentNames.NAME_NORMALIZER,
                    "Normalizes names — romanization, OCR healing, aliases")
            .inputs("rawNames", "sourceClassification")
            .outputKey("normalizedCandidates")
            .listener(monitor)
            .build();

    // ── Wave 3 ──────────────────────────────────────────────────────────

    private static final AgentSpec DEDUP_LINKER_SPEC = AgentSpec
            .of(AgentNames.DEDUP_LINKER,
                    "Deduplicates persons and links to prevailing source")
            .inputs("normalizedCandidates", "sourceClassification")
            .outputKey("dedupedCandidates")
            .listener(monitor)
            .build();

    // ── Wave 4 ──────────────────────────────────────────────────────────

    private static final AgentSpec CSM_CLASSIFIER_SPEC = AgentSpec
            .of(AgentNames.CSM_CLASSIFIER,
                    "Classifies each candidate for CSM eligibility — universal governance rules")
            .inputs("dedupedCandidates", "sourceText", "sourceClassification")
            .outputKey("classifiedCandidates")
            .listener(monitor)
            .build();

    // ── Wave 5 (parallel) ───────────────────────────────────────────────

    private static final AgentSpec COUNTRY_OVERRIDE_SPEC = AgentSpec
            .of(AgentNames.COUNTRY_OVERRIDE,
                    "Applies country-specific profile overrides to classified candidates")
            .inputs("classifiedCandidates", "sourceClassification")
            .outputKey("countryOverrides")
            .listener(monitor)
            .build();

    private static final AgentSpec TITLE_EXTRACTOR_SPEC = AgentSpec
            .of(AgentNames.TITLE_EXTRACTOR,
                    "Extracts jobTitle and personalTitle with ANCHOR GATE")
            .inputs("classifiedCandidates", "sourceText")
            .outputKey("titleExtractions")
            .listener(monitor)
            .build();

    private static final AgentSpec SCORING_ENGINE_SPEC = AgentSpec
            .of(AgentNames.SCORING_ENGINE,
                    "Computes explanatory D-scores and validates quality gates")
            .inputs("classifiedCandidates")
            .outputKey("scoredCandidates")
            .listener(monitor)
            .build();

    // ── Wave 6 ──────────────────────────────────────────────────────────

    private static final AgentSpec REASON_ASSEMBLER_SPEC = AgentSpec
            .of(AgentNames.REASON_ASSEMBLER,
                    "Assembles canonical reason strings in R2 order")
            .inputs("enrichedCandidates")
            .outputKey("reasonedCandidates")
            .listener(monitor)
            .build();

    // ── Wave 7 ──────────────────────────────────────────────────────────

    private static final AgentSpec OUTPUT_FORMATTER_SPEC = AgentSpec
            .of(AgentNames.OUTPUT_FORMATTER,
                    "Formats final JSON output and validates schema")
            .inputs("reasonedCandidates", "fileNames")
            .outputKey("finalOutput")
            .listener(monitor)
            .build();

    // ── Wave 8 + Loop ───────────────────────────────────────────────────

    private static final AgentSpec FIRST_CRITIC_SPEC = AgentSpec
            .of(AgentNames.EXTRACTION_CRITIC,
                    "Reviews output against compliance checklist")
            .inputs("finalOutput", "sourceText")
            .outputKey("extractionReview")
            .listener(monitor)
            .build();

    private static final AgentSpec OUTPUT_REFINER_SPEC = AgentSpec
            .of(AgentNames.OUTPUT_REFINER,
                    "Fixes critic-identified issues in the final output")
            .inputs("finalOutput", "extractionReview", "enrichedCandidates")
            .outputKey("finalOutput")
            .listener(monitor)
            .build();

    private static final AgentSpec LOOP_CRITIC_SPEC = AgentSpec
            .of(AgentNames.EXTRACTION_CRITIC,
                    "Re-evaluates refined output against compliance checklist")
            .inputs("finalOutput", "sourceText")
            .outputKey("extractionReview")
            .listener(monitor)
            .build();

    // =========================================================================
    //  Public API
    // =========================================================================

    /**
     * Executes the full 12-agent CSM extraction pipeline.
     *
     * @param sourceText raw document text
     * @param fileNames  comma-separated document names
     * @param scope      AgenticScope for state management
     * @return finalOutput as JSON string
     */
    public String execute(String sourceText, String fileNames, AgenticScope scope) {
        log.info("Starting CSM extraction V6 — 12-agent wave pipeline");

        // Seed scope with initial inputs
        scope.writeState("sourceText", sourceText);
        scope.writeState("fileNames", fileNames);

        // ── Wave 1 (parallel): Candidate Extraction ∥ Source Classification ──
        executeWave1Parallel(scope);

        // ── Wave 2: Name Normalization ──────────────────────────────────
        executeWave2(scope);

        // ── Wave 3: Dedup + Source Linkage ──────────────────────────────
        executeWave3(scope);

        // ── Wave 4: CSM Classification ─────────────────────────────────
        executeWave4(scope);

        // ── Wave 5 (parallel): Country ∥ Title ∥ Scoring ───────────────
        executeWave5Parallel(scope);

        // ── Wave 5 merge: combine parallel outputs → enrichedCandidates ─
        mergeWave5Outputs(scope);

        // ── Wave 6: Reason Assembly ────────────────────────────────────
        executeWave6(scope);

        // ── Wave 7: Output Formatting ──────────────────────────────────
        executeWave7(scope);

        // ── Wave 8 + Loop: Critic → Refiner ────────────────────────────
        executeWave8CriticLoop(scope);

        String finalOutput = scope.readState("finalOutput", "{}");
        log.info("CSM extraction V6 complete");
        return finalOutput;
    }

    // =========================================================================
    //  Wave Implementations
    // =========================================================================

    /**
     * Wave 1: Runs candidate extractor and source classifier in parallel.
     * Both read sourceText + fileNames but produce independent outputs.
     */
    private void executeWave1Parallel(AgenticScope scope) {
        log.info("Wave 1 — parallel: Candidate Extractor ∥ Source Classifier");

        UntypedAgent candidateExtractor = agentFactory.create(CANDIDATE_EXTRACTOR_SPEC);
        UntypedAgent sourceClassifier   = agentFactory.create(SOURCE_CLASSIFIER_SPEC);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> extractFuture = CompletableFuture.runAsync(
                    () -> candidateExtractor.invoke(scope), executor);
            CompletableFuture<Void> classifyFuture = CompletableFuture.runAsync(
                    () -> sourceClassifier.invoke(scope), executor);

            CompletableFuture.allOf(extractFuture, classifyFuture).join();
        }

        log.info("Wave 1 complete — rawNames and sourceClassification ready");
    }

    /**
     * Wave 2: Name normalization — needs rawNames + sourceClassification.
     */
    private void executeWave2(AgenticScope scope) {
        log.info("Wave 2 — Name Normalizer");
        UntypedAgent nameNormalizer = agentFactory.create(NAME_NORMALIZER_SPEC);
        nameNormalizer.invoke(scope);
        log.info("Wave 2 complete — normalizedCandidates ready");
    }

    /**
     * Wave 3: Deduplication + prevailing source linkage.
     */
    private void executeWave3(AgenticScope scope) {
        log.info("Wave 3 — Dedup + Source Linkage");
        UntypedAgent dedupLinker = agentFactory.create(DEDUP_LINKER_SPEC);
        dedupLinker.invoke(scope);
        log.info("Wave 3 complete — dedupedCandidates ready");
    }

    /**
     * Wave 4: CSM classification — universal governance rules (no CP).
     */
    private void executeWave4(AgenticScope scope) {
        log.info("Wave 4 — CSM Classifier");
        UntypedAgent csmClassifier = agentFactory.create(CSM_CLASSIFIER_SPEC);
        csmClassifier.invoke(scope);
        log.info("Wave 4 complete — classifiedCandidates ready");
    }

    /**
     * Wave 5: Runs country override, title extractor, and scoring engine in parallel.
     * All three read classifiedCandidates but write to different scope keys.
     */
    private void executeWave5Parallel(AgenticScope scope) {
        log.info("Wave 5 — parallel: Country Override ∥ Title Extractor ∥ Scoring Engine");

        UntypedAgent countryOverride = agentFactory.create(COUNTRY_OVERRIDE_SPEC);
        UntypedAgent titleExtractor  = agentFactory.create(TITLE_EXTRACTOR_SPEC);
        UntypedAgent scoringEngine   = agentFactory.create(SCORING_ENGINE_SPEC);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> countryFuture = CompletableFuture.runAsync(
                    () -> countryOverride.invoke(scope), executor);
            CompletableFuture<Void> titleFuture = CompletableFuture.runAsync(
                    () -> titleExtractor.invoke(scope), executor);
            CompletableFuture<Void> scoringFuture = CompletableFuture.runAsync(
                    () -> scoringEngine.invoke(scope), executor);

            CompletableFuture.allOf(countryFuture, titleFuture, scoringFuture).join();
        }

        log.info("Wave 5 complete — countryOverrides, titleExtractions, scoredCandidates ready");
    }

    /**
     * Merges Wave 5 parallel outputs into a single enrichedCandidates JSON.
     *
     * <p>Each Wave 5 agent enriches different fields of the same candidate set:</p>
     * <ul>
     *   <li>Agent 6 (Country Override): may flip {@code isCsm}, adds {@code countryProfileApplied},
     *       {@code countryOverrideNote}</li>
     *   <li>Agent 7 (Title Extractor): adds {@code jobTitle}, {@code personalTitle},
     *       {@code anchorNote}</li>
     *   <li>Agent 8 (Scoring Engine): adds {@code score}, {@code scoreBreakdown},
     *       {@code qualityGateNotes}</li>
     * </ul>
     *
     * <p>Merge strategy: match candidates by {@code id}, overlay each agent's fields
     * onto the base classifiedCandidates. Agent 6's {@code isCsm} override takes
     * precedence over Agent 5's determination (country-specific stricter rules).</p>
     */
    void mergeWave5Outputs(AgenticScope scope) {
        log.info("Merging Wave 5 outputs → enrichedCandidates");

        String classifiedJson     = scope.readState("classifiedCandidates", "{}");
        String countryOverridesJson = scope.readState("countryOverrides", "{}");
        String titleExtractionsJson = scope.readState("titleExtractions", "{}");
        String scoredCandidatesJson = scope.readState("scoredCandidates", "{}");

        try {
            // Parse all outputs
            JsonNode classified = objectMapper.readTree(classifiedJson);
            JsonNode countryOverrides = objectMapper.readTree(countryOverridesJson);
            JsonNode titleExtractions = objectMapper.readTree(titleExtractionsJson);
            JsonNode scoredCandidates = objectMapper.readTree(scoredCandidatesJson);

            // Get the candidate arrays from each output
            ArrayNode baseCandidates = getCandidateArray(classified,
                    "classified_candidates", "candidates");
            ArrayNode countryArray = getCandidateArray(countryOverrides,
                    "country_overrides", "candidates");
            ArrayNode titleArray = getCandidateArray(titleExtractions,
                    "title_extractions", "candidates");
            ArrayNode scoreArray = getCandidateArray(scoredCandidates,
                    "scored_candidates", "candidates");

            // Build enriched candidates by merging fields from each agent
            ArrayNode enriched = objectMapper.createArrayNode();

            for (int i = 0; i < baseCandidates.size(); i++) {
                ObjectNode candidate = baseCandidates.get(i).deepCopy();
                int candidateId = candidate.has("id") ? candidate.get("id").asInt() : i + 1;

                // Merge country override fields (Agent 6)
                JsonNode countryRecord = findById(countryArray, candidateId);
                if (countryRecord != null) {
                    mergeField(candidate, countryRecord, "isCsm");
                    mergeField(candidate, countryRecord, "countryProfileApplied");
                    mergeField(candidate, countryRecord, "countryOverrideNote");
                }

                // Merge title extraction fields (Agent 7)
                JsonNode titleRecord = findById(titleArray, candidateId);
                if (titleRecord != null) {
                    mergeField(candidate, titleRecord, "jobTitle");
                    mergeField(candidate, titleRecord, "personalTitle");
                    mergeField(candidate, titleRecord, "anchorNote");
                }

                // Merge scoring fields (Agent 8)
                JsonNode scoreRecord = findById(scoreArray, candidateId);
                if (scoreRecord != null) {
                    mergeField(candidate, scoreRecord, "score");
                    mergeField(candidate, scoreRecord, "scoreBreakdown");
                    mergeField(candidate, scoreRecord, "qualityGateNotes");
                }

                enriched.add(candidate);
            }

            ObjectNode result = objectMapper.createObjectNode();
            result.set("enriched_candidates", enriched);

            String enrichedJson = objectMapper.writeValueAsString(result);
            scope.writeState("enrichedCandidates", enrichedJson);

            log.info("Wave 5 merge complete — {} enriched candidates", enriched.size());

        } catch (JsonProcessingException e) {
            log.error("Failed to merge Wave 5 outputs", e);
            // Fallback: use scoredCandidates as enrichedCandidates
            scope.writeState("enrichedCandidates", scoredCandidatesJson);
        }
    }

    /**
     * Wave 6: Reason assembly — reads enrichedCandidates (all Wave 5 tags present).
     */
    private void executeWave6(AgenticScope scope) {
        log.info("Wave 6 — Reason Assembler");
        UntypedAgent reasonAssembler = agentFactory.create(REASON_ASSEMBLER_SPEC);
        reasonAssembler.invoke(scope);
        log.info("Wave 6 complete — reasonedCandidates ready");
    }

    /**
     * Wave 7: Output formatting and schema validation.
     */
    private void executeWave7(AgenticScope scope) {
        log.info("Wave 7 — Output Formatter");
        UntypedAgent outputFormatter = agentFactory.create(OUTPUT_FORMATTER_SPEC);
        outputFormatter.invoke(scope);
        log.info("Wave 7 complete — finalOutput ready");
    }

    /**
     * Wave 8: First critic, then refiner loop.
     * Loop exit condition: deserialize extractionReview → DTO → check score.
     */
    private void executeWave8CriticLoop(AgenticScope scope) {
        log.info("Wave 8 — First Critic + Refiner Loop");

        // First critic (bootstraps extractionReview)
        UntypedAgent firstCritic = agentFactory.create(FIRST_CRITIC_SPEC);
        firstCritic.invoke(scope);

        // Check if refinement is needed
        String reviewJson = scope.readState("extractionReview", "");
        double score = parseExtractionScore(reviewJson);
        log.info("First critic score: {}", score);

        if (score >= EXTRACTION_QUALITY_THRESHOLD) {
            log.info("Score >= {} — no refinement needed", EXTRACTION_QUALITY_THRESHOLD);
            return;
        }

        // Refiner loop
        for (int iteration = 1; iteration <= REFINEMENT_LOOP_MAX_ITERATIONS; iteration++) {
            log.info("Refinement iteration {}/{}", iteration, REFINEMENT_LOOP_MAX_ITERATIONS);

            // Run refiner
            UntypedAgent refiner = agentFactory.create(OUTPUT_REFINER_SPEC);
            refiner.invoke(scope);

            // Run critic
            UntypedAgent loopCritic = agentFactory.create(LOOP_CRITIC_SPEC);
            loopCritic.invoke(scope);

            // Check exit condition
            reviewJson = scope.readState("extractionReview", "");
            score = parseExtractionScore(reviewJson);
            log.info("Refinement iteration {} — critic score: {}", iteration, score);

            if (score >= EXTRACTION_QUALITY_THRESHOLD) {
                log.info("Score >= {} — refinement loop complete", EXTRACTION_QUALITY_THRESHOLD);
                return;
            }
        }

        log.warn("Refinement loop exhausted {} iterations — score: {}",
                REFINEMENT_LOOP_MAX_ITERATIONS, score);
    }

    // =========================================================================
    //  Internal Helpers
    // =========================================================================

    /**
     * Finds a candidate array inside a JSON response.
     * Tries multiple common key names since different agents use different keys.
     */
    private ArrayNode getCandidateArray(JsonNode root, String... possibleKeys) {
        for (String key : possibleKeys) {
            if (root.has(key) && root.get(key).isArray()) {
                return (ArrayNode) root.get(key);
            }
        }
        // If root itself is an array
        if (root.isArray()) {
            return (ArrayNode) root;
        }
        return objectMapper.createArrayNode();
    }

    /**
     * Finds a candidate record by id within an array.
     */
    private JsonNode findById(ArrayNode array, int id) {
        for (JsonNode node : array) {
            if (node.has("id") && node.get("id").asInt() == id) {
                return node;
            }
        }
        return null;
    }

    /**
     * Merges a single field from source to target if it exists and is not null.
     */
    private void mergeField(ObjectNode target, JsonNode source, String fieldName) {
        if (source.has(fieldName) && !source.get(fieldName).isNull()) {
            target.set(fieldName, source.get(fieldName).deepCopy());
        }
    }

    /**
     * Parses extraction_score from the critic's JSON review string.
     * Deserializes to ExtractionReview DTO for structured access.
     * Falls back to manual parsing if deserialization fails.
     */
    double parseExtractionScore(String reviewJson) {
        if (reviewJson == null || reviewJson.isBlank()) return 0.0;

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
}