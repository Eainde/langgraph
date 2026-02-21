package com.eainde.agent.mapper_reducer;

import com.db.clm.kyc.ai.chunking.DocumentChunker;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.scope.AgenticScope;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

/**
 * Top-level entry point for CSM extraction.
 * Reads sourceText from scope, decides whether to use direct or chunked path.
 *
 * <pre>
 * invoke(scope)
 *   │
 *   ├── sourceText fits in token limit?
 *   │     ├── YES → buildDirectWorkflow().invoke(scope)
 *   │     └── NO  → buildChunkedWorkflow().invoke(scope)
 *   │
 *   └── "finalOutput" is in scope
 * </pre>
 *
 * <h3>Usage from LangGraph node:</h3>
 * <pre>
 * // scope already has "sourceText" and "fileNames" from upstream
 * routingExtractionAgent.invoke(scope);
 * // scope now has "finalOutput"
 * </pre>
 */
@Log4j2
@Component
public class RoutingExtractionAgent implements UntypedAgent {

    private final CsmExtractionWorkflowConfigV6 workflowConfig;

    public RoutingExtractionAgent(CsmExtractionWorkflowConfigV6 workflowConfig) {
        this.workflowConfig = workflowConfig;
    }

    @Override
    public void invoke(AgenticScope scope) {
        Object sourceTextObj = scope.readState("sourceText");
        if (sourceTextObj == null) {
            throw new IllegalStateException(
                    "sourceText not found in scope — upstream node must seed it");
        }
        String sourceText = sourceTextObj.toString();

        Object fileNamesObj = scope.readState("fileNames");
        String fileNames = fileNamesObj != null ? fileNamesObj.toString() : "";
        if (fileNames.isBlank()) {
            log.warn("fileNames is empty in scope — agents may produce incomplete output");
        }

        boolean needsChunking = workflowConfig.isChunkingEnabled()
                && DocumentChunker.estimateTokens(sourceText) > workflowConfig.getMaxTokenEstimate();

        if (!needsChunking) {
            log.info("Document fits within token limit ({} estimated tokens) — using DIRECT path",
                    DocumentChunker.estimateTokens(sourceText));
            workflowConfig.buildDirectWorkflow().invoke(scope);
        } else {
            log.info("Document exceeds token limit ({} estimated tokens > {}) — using CHUNKED path",
                    DocumentChunker.estimateTokens(sourceText),
                    workflowConfig.getMaxTokenEstimate());
            workflowConfig.buildChunkedWorkflow().invoke(scope);
        }

        // Verify finalOutput was produced
        Object finalOutput = scope.readState("finalOutput");
        if (finalOutput == null) {
            log.error("Pipeline completed but finalOutput is null in scope");
        } else {
            log.info("CSM extraction complete — finalOutput in scope");
        }
    }
}
