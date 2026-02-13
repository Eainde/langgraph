package com.eainde.agent;

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
 * Defines and assembles the CSM extraction pipeline.
 *
 * Each agent is declared as an AgentSpec (metadata only — prompts come from DB).
 * The AgentFactory handles all the build mechanics.
 *
 * CURRENT PIPELINE (simple sequence):
 *   sourceText → [Validate] → [Extract] → [Score] → [Audit] → qaReport
 *
 * TO ADD A CRITIC-VALIDATOR LOOP, simply compose with agentFactory.loop().
 * See the commented example at the bottom.
 */
@Configuration
public class CsmWorkflowConfig {

    private static final Logger log = LoggerFactory.getLogger(CsmWorkflowConfig.class);

    private final AgentFactory agentFactory;
    private final WorkflowHolder workflowHolder;

    public CsmWorkflowConfig(AgentFactory agentFactory, WorkflowHolder workflowHolder) {
        this.agentFactory = agentFactory;
        this.workflowHolder = workflowHolder;
    }

    // =========================================================================
    //  Agent Specs — declarative, no prompts, no boilerplate
    // =========================================================================

    private static final AgentSpec SOURCE_VALIDATOR = AgentSpec
            .of(AgentNames.SOURCE_VALIDATOR, "Validates and weights the source document")
            .inputs("sourceText")
            .outputKey("sourceValidation")
            .build();

    private static final AgentSpec ENTITY_EXTRACTOR = AgentSpec
            .of(AgentNames.ENTITY_EXTRACTOR, "Extracts CSM candidates from the source file")
            .inputs("sourceText")
            .outputKey("candidates")
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

    /**
     * Builds all workflows from specs and registers in the holder.
     * Called at startup and on prompt refresh.
     */
    public void buildAndRegisterWorkflows() {
        log.info("Building CSM workflows from agent specs...");

        workflowHolder.setExtractionWorkflow(buildExtractionWorkflow());
        workflowHolder.setConsolidationWorkflow(buildConsolidationWorkflow());

        log.info("CSM workflows built and registered.");
    }

    private CsmExtractionWorkflow buildExtractionWorkflow() {
        // Build individual agents from specs
        UntypedAgent[] agents = agentFactory.createAll(
                SOURCE_VALIDATOR,
                ENTITY_EXTRACTOR,
                LOGICAL_ANALYST,
                QUALITY_AUDITOR
        );

        // Wire into typed sequential workflow
        return agentFactory.sequence(CsmExtractionWorkflow.class, "qaReport", agents);
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
    //  EXAMPLE: How to add a critic-validator loop
    // =========================================================================
    //
    //  Say you want the LogicalAnalyst to be reviewed by a critic,
    //  and loop until the score is good enough:
    //
    //  private static final AgentSpec SCORING_CRITIC = AgentSpec
    //          .of("scoring-critic", "Reviews scoring quality")
    //          .inputs("scoredCandidates")
    //          .outputKey("scoringQuality")
    //          .build();
    //
    //  Then in buildExtractionWorkflow():
    //
    //      UntypedAgent sourceValidator = agentFactory.create(SOURCE_VALIDATOR);
    //      UntypedAgent entityExtractor = agentFactory.create(ENTITY_EXTRACTOR);
    //
    //      // Loop: critic evaluates → analyst refines, until quality >= 0.8
    //      UntypedAgent scoringLoop = agentFactory.loop(
    //              5,  // max iterations
    //              scope -> scope.readState("scoringQuality", 0.0) >= 0.8,
    //              SCORING_CRITIC, LOGICAL_ANALYST
    //      );
    //
    //      UntypedAgent qualityAuditor = agentFactory.create(QUALITY_AUDITOR);
    //
    //      return agentFactory.sequence(
    //              CsmExtractionWorkflow.class, "qaReport",
    //              sourceValidator, entityExtractor, scoringLoop, qualityAuditor
    //      );
    //
    //  That's it. The loop is just another agent in the sequence.
    //  Add the "scoring-critic" prompt to the DB and you're done.
}