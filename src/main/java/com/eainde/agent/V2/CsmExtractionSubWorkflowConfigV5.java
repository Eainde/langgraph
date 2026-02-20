package com.eainde.agent.V2;

package com.db.clm.kyc.ai.agents;

import com.db.clm.kyc.ai.config.AgentFactory;
import com.db.clm.kyc.ai.config.AgentSpec;
import com.db.clm.kyc.ai.prompt.AgentNames;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.observability.AgentMonitor;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Configuration;

/**
 * CSM Extraction Pipeline V5 — 7-agent decomposition of the monolithic extraction prompt.
 *
 * <pre>
 * PIPELINE FLOW:
 *
 *   sourceText + fileNames
 *     │
 *     ▼
 *   ┌─────────────────────────────┐
 *   │ 1. SOURCE CLASSIFIER        │  B, IDandV Tab 2
 *   │    csm-source-classifier    │
 *   └──────────┬──────────────────┘
 *              │ sourceClassification
 *              ▼
 *   ┌─────────────────────────────┐
 *   │ 2. PERSON EXTRACTOR         │  RC1-3, L1-9, JT, PT, A6
 *   │    csm-person-extractor     │
 *   └──────────┬──────────────────┘
 *              │ rawCandidates
 *              ▼
 *   ┌─────────────────────────────┐
 *   │ 3. CSM CLASSIFIER           │  A1-5, C1-13, CP, RC4-7
 *   │    csm-classifier           │
 *   └──────────┬──────────────────┘
 *              │ classifiedCandidates
 *              ▼
 *   ┌─────────────────────────────┐
 *   │ 4. SCORER                   │  D0-6, QG1-10
 *   │    csm-scorer               │
 *   └──────────┬──────────────────┘
 *              │ scoredCandidates
 *              ▼
 *   ┌─────────────────────────────┐
 *   │ 5. OUTPUT ASSEMBLER         │  R1-8, B2-3, J1-7, RC8-9, Z
 *   │    csm-output-assembler     │
 *   └──────────┬──────────────────┘
 *              │ finalOutput
 *              ▼
 *   ┌─────────────────────────────┐
 *   │ 6. FIRST CRITIC             │  Condensed checklist
 *   │    csm-extraction-critic    │
 *   └──────────┬──────────────────┘
 *              │ extractionReview
 *              ▼
 *   ┌─── REFINEMENT LOOP (max 3) ─────────────────┐
 *   │  exit: extraction_score >= 0.85? → DONE      │
 *   │                                               │
 *   │  ┌───────────────────────────┐               │
 *   │  │ 7. OUTPUT REFINER         │               │
 *   │  │    csm-output-refiner     │               │
 *   │  └──────────┬────────────────┘               │
 *   │             │ finalOutput (corrected)         │
 *   │             ▼                                  │
 *   │  ┌───────────────────────────┐               │
 *   │  │ 6. LOOP CRITIC            │               │
 *   │  │    csm-extraction-critic  │               │
 *   │  └──────────┬────────────────┘               │
 *   │             │ extractionReview                │
 *   │             ▼ exit check                      │
 *   └───────────────────────────────────────────────┘
 *              │
 *              ▼ finalOutput
 * </pre>
 *
 * <h3>Agent-to-Section Mapping:</h3>
 * <table>
 *   <tr><th>Agent</th><th>Prompt Sections</th><th>Inputs</th><th>Output</th></tr>
 *   <tr><td>source-classifier</td><td>B, IDandV</td><td>sourceText, fileNames</td><td>sourceClassification</td></tr>
 *   <tr><td>person-extractor</td><td>RC1-3, L, JT, PT, A6</td><td>sourceText, sourceClassification, fileNames</td><td>rawCandidates</td></tr>
 *   <tr><td>csm-classifier</td><td>A, C, CP, RC4-7</td><td>rawCandidates, sourceText, sourceClassification</td><td>classifiedCandidates</td></tr>
 *   <tr><td>scorer</td><td>D, QG</td><td>classifiedCandidates</td><td>scoredCandidates</td></tr>
 *   <tr><td>output-assembler</td><td>R, B2-3, J, RC8-9, Z</td><td>scoredCandidates, fileNames</td><td>finalOutput</td></tr>
 *   <tr><td>extraction-critic</td><td>Checklist</td><td>finalOutput, sourceText</td><td>extractionReview</td></tr>
 *   <tr><td>output-refiner</td><td>R, J, B2 + feedback</td><td>finalOutput, extractionReview, scoredCandidates</td><td>finalOutput</td></tr>
 * </table>
 */
@Log4j2
@Configuration
@RequiredArgsConstructor
public class CsmExtractionSubWorkflowConfigV5 {

    private static final int    REFINEMENT_LOOP_MAX_ITERATIONS = 3;
    private static final double EXTRACTION_QUALITY_THRESHOLD   = 0.85;

    private final AgentFactory agentFactory;

    private static final AgentMonitor monitor = new AgentMonitor();

    // =========================================================================
    //  Agent Specs
    // =========================================================================

    // ── Phase 1: Source Classification ───────────────────────────────────
    // Sections: B (Source Admission Policy), IDandV Tab 2, B0-B4
    // Ranks and classifies all input documents by hierarchy and currency.
    public static final AgentSpec SOURCE_CLASSIFIER = AgentSpec.of(
                    AgentNames.SOURCE_CLASSIFIER,
                    "Ranks and classifies input documents using IDandV hierarchy")
            .inputs("sourceText", "fileNames")
            .outputKey("sourceClassification")
            .listener(monitor)
            .build();

    // ── Phase 2: Person Extraction ──────────────────────────────────────
    // Sections: RC1-RC3, L1-L9, JT, PT, A6, ANCHOR GATE
    // Extracts all persons, parses names, extracts titles, de-duplicates.
    public static final AgentSpec PERSON_EXTRACTOR = AgentSpec.of(
                    AgentNames.PERSON_EXTRACTOR,
                    "Extracts all persons with parsed names, titles, and de-duplication")
            .inputs("sourceText", "sourceClassification", "fileNames")
            .outputKey("rawCandidates")
            .listener(monitor)
            .build();

    // ── Phase 3: CSM Classification ─────────────────────────────────────
    // Sections: A1-A5, C1-C13, CP (all country profiles), RC4-RC7
    // Determines isCsm for each candidate using governance rules.
    public static final AgentSpec CSM_CLASSIFIER = AgentSpec.of(
                    AgentNames.CSM_CLASSIFIER,
                    "Classifies each candidate for CSM eligibility using governance rules")
            .inputs("rawCandidates", "sourceText", "sourceClassification")
            .outputKey("classifiedCandidates")
            .listener(monitor)
            .build();

    // ── Phase 4: Scoring ────────────────────────────────────────────────
    // Sections: D0-D6, QG1-QG10
    // Computes explanatory scores and validates quality gates.
    public static final AgentSpec SCORER = AgentSpec.of(
                    AgentNames.SCORER,
                    "Computes D-scores and validates quality gates")
            .inputs("classifiedCandidates")
            .outputKey("scoredCandidates")
            .listener(monitor)
            .build();

    // ── Phase 5: Output Assembly ────────────────────────────────────────
    // Sections: R1-R8, B2-B3, J1-J7, RC8-RC9, Z1-Z7
    // Assembles final extracted_records JSON with reason strings.
    public static final AgentSpec OUTPUT_ASSEMBLER = AgentSpec.of(
                    AgentNames.OUTPUT_ASSEMBLER,
                    "Assembles final extracted_records JSON with canonical reason strings")
            .inputs("scoredCandidates", "fileNames")
            .outputKey("finalOutput")
            .listener(monitor)
            .build();

    // ── Phase 6a: First Critic (runs before loop) ───────────────────────
    // Condensed compliance checklist across all sections.
    // Reviews finalOutput against the complete rule set.
    public static final AgentSpec FIRST_CRITIC = AgentSpec.of(
                    AgentNames.EXTRACTION_CRITIC,
                    "Reviews final output against compliance checklist")
            .inputs("finalOutput", "sourceText")
            .outputKey("extractionReview")
            .listener(monitor)
            .build();

    // ── Phase 7: Output Refiner (inside loop) ───────────────────────────
    // Sections: R, J, B2 + critic feedback
    // Fixes ONLY the specific issues identified by the critic.
    public static final AgentSpec OUTPUT_REFINER = AgentSpec.of(
                    AgentNames.OUTPUT_REFINER,
                    "Fixes critic-identified issues in the final output")
            .inputs("finalOutput", "extractionReview", "scoredCandidates")
            .outputKey("finalOutput")
            .listener(monitor)
            .build();

    // ── Phase 6b: Loop Critic (inside loop) ─────────────────────────────
    // Same prompt as first critic, re-evaluates after refinement.
    public static final AgentSpec LOOP_CRITIC = AgentSpec.of(
                    AgentNames.EXTRACTION_CRITIC,
                    "Re-evaluates refined output against compliance checklist")
            .inputs("finalOutput", "sourceText")
            .outputKey("extractionReview")
            .listener(monitor)
            .build();

    // =========================================================================
    //  Build Pipeline
    // =========================================================================

    /**
     * Builds the complete CSM extraction sub-workflow.
     *
     * <p>Pipeline structure:</p>
     * <pre>
     *   sequence(
     *     sourceClassifier,
     *     personExtractor,
     *     csmClassifier,
     *     scorer,
     *     outputAssembler,
     *     firstCritic,            ← bootstraps "extractionReview" before loop
     *     loop(                   ← exit check runs FIRST (if score >= 0.85, skip refinement)
     *       outputRefiner,        ← reads extractionReview ✅ (guaranteed to exist)
     *       loopCritic            ← overwrites extractionReview
     *     )
     *   )
     * </pre>
     *
     * @return the complete extraction sub-workflow as an UntypedAgent
     */
    public UntypedAgent buildExtractionSubWorkflow() {
        log.info("Building CSM extraction sub-workflow V5 (7-agent pipeline)...");

        // ── Sequential agents (Phases 1–5) ──────────────────────────────
        UntypedAgent sourceClassifier = agentFactory.create(SOURCE_CLASSIFIER);
        UntypedAgent personExtractor  = agentFactory.create(PERSON_EXTRACTOR);
        UntypedAgent csmClassifier    = agentFactory.create(CSM_CLASSIFIER);
        UntypedAgent scorer           = agentFactory.create(SCORER);
        UntypedAgent outputAssembler  = agentFactory.create(OUTPUT_ASSEMBLER);

        // ── First critic (Phase 6a) — runs ONCE before loop ─────────────
        // This bootstraps "extractionReview" so it exists when the loop starts.
        // Without this, the loop's OUTPUT_REFINER would crash because its
        // prompt contains {{extractionReview}} which doesn't exist yet.
        UntypedAgent firstCritic = agentFactory.create(FIRST_CRITIC);

        // ── Refinement loop (Phases 7 + 6b) ─────────────────────────────
        // Exit check runs FIRST each iteration:
        //   extraction_score >= 0.85 → DONE (no unnecessary refinement)
        //   else → refiner fixes issues → critic re-evaluates → check again
        //
        // If first critic already scores >= 0.85, loop exits immediately
        // with zero refinement calls — no wasted LLM tokens.
        UntypedAgent refinementLoop = agentFactory.loop(
                REFINEMENT_LOOP_MAX_ITERATIONS,
                scope -> parseExtractionScore(
                        scope.readState("extractionReview", "NO"))
                        >= EXTRACTION_QUALITY_THRESHOLD,
                OUTPUT_REFINER, LOOP_CRITIC
        );

        // ── Assemble full pipeline ──────────────────────────────────────
        // sequence: phases 1→2→3→4→5 → firstCritic → refinementLoop
        UntypedAgent pipeline = agentFactory.sequence(
                "finalOutput",
                sourceClassifier,
                personExtractor,
                csmClassifier,
                scorer,
                outputAssembler,
                firstCritic,
                refinementLoop
        );

        log.info("CSM extraction sub-workflow V5 built successfully.");
        return pipeline;
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    /**
     * Parses the extraction_score from the critic's JSON review.
     * Returns 0.0 if the review is missing, blank, or unparseable.
     *
     * <p>Expected format in extractionReview:</p>
     * <pre>{"issues": [...], "extraction_score": 0.92, "summary": "..."}</pre>
     */
    static double parseExtractionScore(String extractionReview) {
        if (extractionReview == null || extractionReview.isBlank()) return 0.0;
        try {
            String key = "\"extraction_score\"";
            int idx = extractionReview.indexOf(key);
            if (idx == -1) return 0.0;
            int colonIdx = extractionReview.indexOf(':', idx + key.length());
            if (colonIdx == -1) return 0.0;
            StringBuilder sb = new StringBuilder();
            for (int i = colonIdx + 1; i < extractionReview.length(); i++) {
                char c = extractionReview.charAt(i);
                if (Character.isDigit(c) || c == '.') sb.append(c);
                else if (!Character.isWhitespace(c) && sb.length() > 0) break;
            }
            return sb.length() > 0 ? Double.parseDouble(sb.toString()) : 0.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
