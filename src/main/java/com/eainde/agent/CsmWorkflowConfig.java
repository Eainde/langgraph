package com.db.clm.kyc.ai.config;

import com.db.clm.kyc.ai.agents.CsmConsolidationWorkflow;
import com.db.clm.kyc.ai.agents.CsmExtractionWorkflow;
import com.db.clm.kyc.ai.prompt.AgentNames;
import dev.langchain4j.agent.AgenticServices;
import dev.langchain4j.agent.UntypedAgent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * CSM extraction pipeline with a critic-validator loop around entity extraction.
 *
 * FLOW:
 *
 *   sourceText
 *     │
 *     ▼
 *   ┌──────────────────────────────┐
 *   │  1. csm-source-validator     │  → "sourceValidation"
 *   └──────────────┬───────────────┘
 *                  │
 *                  ▼
 *   ┌──────────────────────────────────────────────────────┐
 *   │  2. ENTITY EXTRACTION LOOP (max 3 iterations)       │
 *   │  ┌────────────────────────────┐                      │
 *   │  │  entity-extractor          │ → "candidates"       │
 *   │  │  (reads: sourceText,       │                      │
 *   │  │   extractionReview if any) │                      │
 *   │  └────────────┬───────────────┘                      │
 *   │               │                                      │
 *   │               ▼                                      │
 *   │  ┌────────────────────────────┐                      │
 *   │  │  entity-extraction-critic  │ → "extractionReview" │
 *   │  │  (reads: candidates,       │                      │
 *   │  │   sourceText)              │                      │
 *   │  └────────────────────────────┘                      │
 *   │       ↑                                              │
 *   │       └──── loop if extractionScore < 0.8 ──────────│
 *   └──────────────────────────────────────────────────────┘
 *                  │
 *                  ▼
 *   ┌──────────────────────────────┐
 *   │  3. logical-analyst          │  → "scoredCandidates"
 *   └──────────────┬───────────────┘
 *                  │
 *                  ▼
 *   ┌──────────────────────────────┐
 *   │  4. quality-auditor          │  → "qaReport"
 *   └──────────────────────────────┘
 */
@Configuration
public class CsmWorkflowConfig {

    private static final Logger log = LoggerFactory.getLogger(CsmWorkflowConfig.class);

    private static final int    EXTRACTION_LOOP_MAX_ITERATIONS = 3;
    private static final double EXTRACTION_QUALITY_THRESHOLD   = 0.8;

    private final AgentFactory agentFactory;
    private final WorkflowHolder workflowHolder;

    public CsmWorkflowConfig(AgentFactory agentFactory, WorkflowHolder workflowHolder) {
        this.agentFactory = agentFactory;
        this.workflowHolder = workflowHolder;
    }

    // =========================================================================
    //  Agent Specs
    // =========================================================================

    private static final AgentSpec SOURCE_VALIDATOR = AgentSpec
            .of(AgentNames.SOURCE_VALIDATOR, "Validates and weights the source document")
            .inputs("sourceText")
            .outputKey("sourceValidation")
            .build();

    private static final AgentSpec ENTITY_EXTRACTOR = AgentSpec
            .of(AgentNames.ENTITY_EXTRACTOR, "Extracts CSM candidates from the source file")
            .inputs("sourceText", "extractionReview")
            .outputKey("candidates")
            .build();

    private static final AgentSpec ENTITY_EXTRACTION_CRITIC = AgentSpec
            .of(AgentNames.ENTITY_EXTRACTION_CRITIC, "Reviews extraction quality and provides feedback")
            .inputs("candidates", "sourceText")
            .outputKey("extractionReview")
            .build();

    private static final AgentSpec LOGICAL_ANALYST = AgentSpec
            .of(AgentNames.LOGICAL_ANALYST, "Scores candidates using evidence model")
            .inputs("candidates", "sourceValidation")
            .outputKey("scoredCandidates")
            .build();

    private static final AgentSpec QUALITY_AUDITOR = AgentSpec
            .of(AgentNames.QUALITY_AUDITOR, "Enforces QA gates, produces audited CSM list")
            .inputs("scoredCandidates")
            .outputKey("qaReport")
            .build();

    private static final AgentSpec CONSOLIDATION = AgentSpec
            .of(AgentNames.CONSOLIDATION, "Merges multi-file results into unified CSM list")
            .inputs("allQaReports")
            .outputKey("consolidatedReport")
            .build();

    // =========================================================================
    //  Build & Register
    // =========================================================================

    @PostConstruct
    public void init() {
        buildAndRegisterWorkflows();
    }

    public void buildAndRegisterWorkflows() {
        log.info("Building CSM workflows from agent specs...");

        workflowHolder.setExtractionWorkflow(buildExtractionWorkflow());
        workflowHolder.setConsolidationWorkflow(buildConsolidationWorkflow());

        log.info("CSM workflows built and registered.");
    }

    private CsmExtractionWorkflow buildExtractionWorkflow() {

        // Agent 1: Source Validator (straight-through)
        UntypedAgent sourceValidator = agentFactory.create(SOURCE_VALIDATOR);

        // Agent 2: Entity Extraction Loop (critic-validator pattern)
        //   - Extractor produces candidates
        //   - Critic reviews and scores them
        //   - If score < threshold, Extractor refines using critic's feedback
        //   - Loop exits when quality is met or max iterations reached
        UntypedAgent extractionLoop = agentFactory.loopAtEnd(
                EXTRACTION_LOOP_MAX_ITERATIONS,
                scope -> parseExtractionScore(scope.readState("extractionReview", "")) >= EXTRACTION_QUALITY_THRESHOLD,
                ENTITY_EXTRACTOR, ENTITY_EXTRACTION_CRITIC
        );

        // Agent 3: Logical Analyst (straight-through)
        UntypedAgent logicalAnalyst = agentFactory.create(LOGICAL_ANALYST);

        // Agent 4: Quality Auditor (straight-through)
        UntypedAgent qualityAuditor = agentFactory.create(QUALITY_AUDITOR);

        // Wire into typed sequential workflow
        return agentFactory.sequence(
                CsmExtractionWorkflow.class, "qaReport",
                sourceValidator, extractionLoop, logicalAnalyst, qualityAuditor
        );
    }

    private CsmConsolidationWorkflow buildConsolidationWorkflow() {
        UntypedAgent agent = agentFactory.create(CONSOLIDATION);

        return AgenticServices
                .sequenceBuilder(CsmConsolidationWorkflow.class)
                .subAgents(agent)
                .outputKey("consolidatedReport")
                .build();
    }

    // =========================================================================
    //  Helpers
    // =========================================================================

    /**
     * Parses the extraction_score from the critic's JSON review output.
     * The critic returns JSON like: { "extraction_score": 0.85, "feedback": "..." }
     * We do a lightweight parse to avoid adding a JSON library dependency here.
     */
    private static double parseExtractionScore(String extractionReview) {
        if (extractionReview == null || extractionReview.isBlank()) {
            return 0.0;
        }
        try {
            // Look for "extraction_score": <number> in the JSON
            String key = "\"extraction_score\"";
            int idx = extractionReview.indexOf(key);
            if (idx == -1) {
                return 0.0;
            }
            int colonIdx = extractionReview.indexOf(':', idx + key.length());
            if (colonIdx == -1) {
                return 0.0;
            }
            // Extract the number after the colon
            StringBuilder sb = new StringBuilder();
            for (int i = colonIdx + 1; i < extractionReview.length(); i++) {
                char c = extractionReview.charAt(i);
                if (Character.isDigit(c) || c == '.') {
                    sb.append(c);
                } else if (!Character.isWhitespace(c) && sb.length() > 0) {
                    break;
                }
            }
            return sb.length() > 0 ? Double.parseDouble(sb.toString()) : 0.0;
        } catch (NumberFormatException e) {
            log.warn("Failed to parse extraction_score from review: {}", extractionReview);
            return 0.0;
        }
    }
}