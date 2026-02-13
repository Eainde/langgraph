package com.eainde.agent;

package com.db.clm.kyc.ai.config;

import com.db.clm.kyc.ai.agents.CsmConsolidationWorkflow;
import com.db.clm.kyc.ai.agents.CsmExtractionWorkflow;
import com.db.clm.kyc.ai.prompt.AgentNames;
import com.db.clm.kyc.ai.prompt.PromptService;
import dev.langchain4j.agent.AgenticServices;
import dev.langchain4j.agent.UntypedAgent;
import dev.langchain4j.model.chat.ChatLanguageModel;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * Builds and manages the CSM extraction workflow with externalized DB prompts.
 *
 * All agents are built as UntypedAgents using prompts from PromptService.
 * Workflow instances are stored in WorkflowHolder (AtomicReference-based)
 * to support hot-reload when prompts change — no application restart needed.
 *
 * Agent data flow through AgenticScope:
 *
 *   sourceText → [csm-source-validator] → "sourceValidation"
 *                [entity-extractor]     → "candidates"
 *                [logical-analyst]      → "scoredCandidates"   (reads: candidates + sourceValidation)
 *                [quality-auditor]      → "qaReport"           (reads: scoredCandidates)
 *
 *   Per-file qaReports → [csm-consolidation] → "consolidatedReport"
 */
@Configuration
public class CsmWorkflowConfig {

    private static final Logger log = LoggerFactory.getLogger(CsmWorkflowConfig.class);

    private final ChatLanguageModel chatModel;
    private final PromptService promptService;
    private final WorkflowHolder workflowHolder;

    public CsmWorkflowConfig(ChatLanguageModel chatModel,
                             PromptService promptService,
                             WorkflowHolder workflowHolder) {
        this.chatModel = chatModel;
        this.promptService = promptService;
        this.workflowHolder = workflowHolder;
    }

    /** Build workflows on startup after all beans are ready. */
    @PostConstruct
    public void init() {
        buildAndRegisterWorkflows();
    }

    /**
     * Builds all agents with current DB prompts and registers them in the holder.
     * Called at startup and on prompt refresh.
     */
    public void buildAndRegisterWorkflows() {
        log.info("Building CSM workflows with externalized prompts from database...");

        workflowHolder.setExtractionWorkflow(buildExtractionWorkflow());
        workflowHolder.setConsolidationWorkflow(buildConsolidationWorkflow());

        log.info("CSM workflows built and registered successfully.");
    }

    private CsmExtractionWorkflow buildExtractionWorkflow() {

        // Agent 1: Source Validator
        UntypedAgent sourceValidator = AgenticServices.agentBuilder()
                .chatModel(chatModel)
                .name(AgentNames.SOURCE_VALIDATOR)
                .description("Validates and weights the source document based on hierarchy and recency rules")
                .systemMessage(promptService.getSystemPrompt(AgentNames.SOURCE_VALIDATOR))
                .userMessage(promptService.getUserPrompt(AgentNames.SOURCE_VALIDATOR))
                .inputKey(String.class, "sourceText")
                .returnType(String.class)
                .outputKey("sourceValidation")
                .build();

        // Agent 2: Entity Extractor
        UntypedAgent entityExtractor = AgenticServices.agentBuilder()
                .chatModel(chatModel)
                .name(AgentNames.ENTITY_EXTRACTOR)
                .description("Extracts and classifies potential CSM candidates from the raw source file")
                .systemMessage(promptService.getSystemPrompt(AgentNames.ENTITY_EXTRACTOR))
                .userMessage(promptService.getUserPrompt(AgentNames.ENTITY_EXTRACTOR))
                .inputKey(String.class, "sourceText")
                .returnType(String.class)
                .outputKey("candidates")
                .build();

        // Agent 3: Logical Analyst
        // User prompt template references {{candidates}} + {{sourceValidation}} from AgenticScope
        UntypedAgent logicalAnalyst = AgenticServices.agentBuilder()
                .chatModel(chatModel)
                .name(AgentNames.LOGICAL_ANALYST)
                .description("Scores and classifies CSM candidates using evidence model and source weight")
                .systemMessage(promptService.getSystemPrompt(AgentNames.LOGICAL_ANALYST))
                .userMessage(promptService.getUserPrompt(AgentNames.LOGICAL_ANALYST))
                .inputKey(String.class, "candidates")
                .inputKey(String.class, "sourceValidation")
                .returnType(String.class)
                .outputKey("scoredCandidates")
                .build();

        // Agent 4: Quality Auditor
        UntypedAgent qualityAuditor = AgenticServices.agentBuilder()
                .chatModel(chatModel)
                .name(AgentNames.QUALITY_AUDITOR)
                .description("Enforces QA gates and produces final audited CSM list")
                .systemMessage(promptService.getSystemPrompt(AgentNames.QUALITY_AUDITOR))
                .userMessage(promptService.getUserPrompt(AgentNames.QUALITY_AUDITOR))
                .inputKey(String.class, "scoredCandidates")
                .returnType(String.class)
                .outputKey("qaReport")
                .build();

        // Wire into typed sequential workflow
        return AgenticServices
                .sequenceBuilder(CsmExtractionWorkflow.class)
                .subAgents(sourceValidator, entityExtractor, logicalAnalyst, qualityAuditor)
                .outputKey("qaReport")
                .build();
    }

    private CsmConsolidationWorkflow buildConsolidationWorkflow() {

        UntypedAgent consolidationAgent = AgenticServices.agentBuilder()
                .chatModel(chatModel)
                .name(AgentNames.CONSOLIDATION)
                .description("Consolidates multiple per-file QA reports into a unified, de-duplicated CSM list")
                .systemMessage(promptService.getSystemPrompt(AgentNames.CONSOLIDATION))
                .userMessage(promptService.getUserPrompt(AgentNames.CONSOLIDATION))
                .inputKey(String.class, "allQaReports")
                .returnType(String.class)
                .outputKey("consolidatedReport")
                .build();

        return AgenticServices
                .sequenceBuilder(CsmConsolidationWorkflow.class)
                .subAgents(consolidationAgent)
                .outputKey("consolidatedReport")
                .build();
    }
}