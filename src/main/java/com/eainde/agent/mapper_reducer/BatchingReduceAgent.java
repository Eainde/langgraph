package com.eainde.agent.mapper_reducer;

import com.db.clm.kyc.ai.chunking.CandidateBatcher;
import com.db.clm.kyc.ai.config.AgentFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.scope.AgenticScope;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayList;
import java.util.List;

/**
 * REDUCE Phase — runs agents 4-8 + Wave5Merge with optional batching.
 *
 * <p>This agent orchestrates the batchable portion of the pipeline. If candidate
 * count exceeds the batch size, it splits candidates, runs agents 4-8 per batch,
 * and merges results. Agents 9-12 run OUTSIDE this agent on the full set.</p>
 *
 * <h3>Batching boundary (per architecture diagram):</h3>
 * <pre>
 * Batchable:     Agent 4 → 5 → 6 → 7 → 8 → Wave5Merge
 * NOT batchable: Agent 9 → 10 → 11 → 12 (need full set, run after this agent)
 * </pre>
 *
 * <h3>Scope overwrite pattern (same as MapPhaseAgent):</h3>
 * <pre>
 * invoke(scope):
 *   if candidateCount <= batchSize:
 *     reduceWorkflow.invoke(scope)                    ← single pass
 *
 *   else:
 *     save scope keys
 *     for each batch:
 *       scope["normalizedCandidates"] = batch.json    ← OVERWRITE
 *       reduceWorkflow.invoke(scope)                   ← agents 4-8
 *       collect scope["enrichedCandidates"]            ← COLLECT
 *
 *     scope["enrichedCandidates"] = merged results     ← MERGED
 *     restore scope keys                               ← RESTORE
 * </pre>
 *
 * <h3>After this agent completes, scope contains:</h3>
 * <ul>
 *   <li>{@code enrichedCandidates} — merged, id-renumbered, full set</li>
 *   <li>{@code sourceText} — original (restored)</li>
 *   <li>{@code sourceClassification} — original (restored)</li>
 * </ul>
 */
@Log4j2
public class BatchingReduceAgent implements UntypedAgent {

    private final AgentFactory agentFactory;
    private final ObjectMapper objectMapper;
    private final boolean batchingEnabled;
    private final int batchSize;

    public BatchingReduceAgent(AgentFactory agentFactory,
                               ObjectMapper objectMapper,
                               boolean batchingEnabled,
                               int batchSize) {
        this.agentFactory = agentFactory;
        this.objectMapper = objectMapper;
        this.batchingEnabled = batchingEnabled;
        this.batchSize = batchSize;
    }

    @Override
    public void invoke(AgenticScope scope) {
        log.info("REDUCE phase — agents 4-8 + Wave5Merge (batching={})", batchingEnabled);

        // ── Build reduce sub-workflow: Agent 4 → 5 → 6 → 7 → 8 → Wave5Merge ──
        Wave5MergerAgent wave5Merger = new Wave5MergerAgent(objectMapper);

        UntypedAgent reduceWorkflow = agentFactory.sequence("enrichedCandidates",
                agentFactory.create(CsmExtractionWorkflowConfigV6.DEDUP_LINKER_SPEC),
                agentFactory.create(CsmExtractionWorkflowConfigV6.CSM_CLASSIFIER_SPEC),
                agentFactory.create(CsmExtractionWorkflowConfigV6.COUNTRY_OVERRIDE_SPEC),
                agentFactory.create(CsmExtractionWorkflowConfigV6.TITLE_EXTRACTOR_SPEC),
                agentFactory.create(CsmExtractionWorkflowConfigV6.SCORING_ENGINE_SPEC),
                wave5Merger);

        // ── Check if batching is needed ─────────────────────────────────
        String normalizedCandidatesJson = readString(scope, "normalizedCandidates");
        CandidateBatcher batcher = new CandidateBatcher(objectMapper, batchSize);
        int candidateCount = batcher.countCandidates(normalizedCandidatesJson);

        if (!batchingEnabled || candidateCount <= batchSize) {
            // ── Single pass: no batching ────────────────────────────────
            log.info("REDUCE — single pass ({} candidates, batch size {})",
                    candidateCount, batchSize);
            reduceWorkflow.invoke(scope);
            log.info("REDUCE complete — enrichedCandidates in scope");
            return;
        }

        // ── Batched execution ───────────────────────────────────────────
        log.info("REDUCE — batching {} candidates into batches of {}",
                candidateCount, batchSize);

        // Split candidates (DTO boundary)
        List<String> batches = batcher.splitCandidatesJson(normalizedCandidatesJson);
        log.info("Split into {} batches", batches.size());

        // Save scope keys that will be overwritten
        String savedNormalizedCandidates = normalizedCandidatesJson;
        String savedSourceClassification = readString(scope, "sourceClassification");
        String savedSourceText = readString(scope, "sourceText");

        // Run reduce workflow per batch
        List<String> batchResults = new ArrayList<>();

        for (int i = 0; i < batches.size(); i++) {
            log.info("REDUCE — batch {}/{}", i + 1, batches.size());

            // OVERWRITE normalizedCandidates with this batch
            scope.writeState("normalizedCandidates", batches.get(i));

            // Run agents 4-8 + Wave5Merge on this batch
            reduceWorkflow.invoke(scope);

            // COLLECT enrichedCandidates for this batch
            String batchEnriched = readString(scope, "enrichedCandidates");
            batchResults.add(batchEnriched);

            log.info("REDUCE — batch {}/{} complete", i + 1, batches.size());
        }

        // ── Merge batch results → renumber ids (J4) ────────────────────
        String mergedEnriched = batcher.mergeEnrichedResultsJson(batchResults);
        scope.writeState("enrichedCandidates", mergedEnriched);

        // ── Restore overwritten scope keys ──────────────────────────────
        scope.writeState("normalizedCandidates", savedNormalizedCandidates);
        scope.writeState("sourceClassification", savedSourceClassification);
        scope.writeState("sourceText", savedSourceText);

        log.info("REDUCE complete — {} batches merged, enrichedCandidates in scope",
                batches.size());
    }

    /**
     * Reads a string from scope, handling null and non-string values.
     */
    private String readString(AgenticScope scope, String key) {
        Object value = scope.readState(key);
        return value != null ? value.toString() : "";
    }
}
