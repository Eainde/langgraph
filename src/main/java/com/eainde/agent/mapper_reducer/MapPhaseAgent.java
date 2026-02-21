package com.eainde.agent.mapper_reducer;

import com.db.clm.kyc.ai.chunking.ChunkContext;
import com.db.clm.kyc.ai.chunking.DocumentChunker;
import com.db.clm.kyc.ai.config.AgentFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.scope.AgenticScope;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayList;
import java.util.List;

/**
 * MAP Phase — runs agents 1-3 per document chunk.
 *
 * <h3>Scope overwrite-collect-repeat pattern:</h3>
 * Since we can't create new AgenticScope instances, this agent reuses the
 * provided scope by overwriting "sourceText" with each chunk's text,
 * invoking the map sub-workflow, collecting outputs, and repeating.
 * Original sourceText is restored after all chunks complete.
 *
 * <pre>
 * invoke(scope):
 *   originalSourceText = scope["sourceText"]
 *
 *   for each chunk:
 *     scope["sourceText"] = chunk.text           ← OVERWRITE
 *     mapWorkflow.invoke(scope)                   ← Agents 1→2→3
 *     collect scope["rawNames"]
 *     collect scope["sourceClassification"]
 *     collect scope["normalizedCandidates"]
 *
 *   scope["sourceText"] = originalSourceText      ← RESTORE
 *   scope["chunkResults"] = aggregated JSON        ← WRITE
 * </pre>
 *
 * <h3>Per-chunk output structure (chunkResults):</h3>
 * <pre>
 * {
 *   "chunks": [
 *     {
 *       "chunkIndex": 0,
 *       "pageStart": 1, "pageEnd": 20,
 *       "overlapStartPage": 1, "overlapEndPage": 5,
 *       "isFirstChunk": true, "isLastChunk": false,
 *       "rawNames": { ... },
 *       "sourceClassification": { ... },
 *       "normalizedCandidates": { ... }
 *     },
 *     ...
 *   ]
 * }
 * </pre>
 */
@Log4j2
public class MapPhaseAgent implements UntypedAgent {

    private final AgentFactory agentFactory;
    private final ObjectMapper objectMapper;
    private final int pagesPerChunk;
    private final int overlapPages;
    private final String pageDelimiterRegex;

    public MapPhaseAgent(AgentFactory agentFactory,
                         ObjectMapper objectMapper,
                         int pagesPerChunk,
                         int overlapPages,
                         String pageDelimiterRegex) {
        this.agentFactory = agentFactory;
        this.objectMapper = objectMapper;
        this.pagesPerChunk = pagesPerChunk;
        this.overlapPages = overlapPages;
        this.pageDelimiterRegex = pageDelimiterRegex;
    }

    @Override
    public void invoke(AgenticScope scope) {
        log.info("MAP phase — starting per-chunk extraction (agents 1-3)");

        // ── Save original state ─────────────────────────────────────────
        String originalSourceText = readString(scope, "sourceText");
        String fileNames = readString(scope, "fileNames");

        // ── Chunk the document ──────────────────────────────────────────
        DocumentChunker chunker = DocumentChunker.builder()
                .pagesPerChunk(pagesPerChunk)
                .overlapPages(overlapPages)
                .pageDelimiter(pageDelimiterRegex)
                .build();

        List<ChunkContext> chunks = chunker.chunk(originalSourceText);
        log.info("Document split into {} chunks ({}pp each, {}pp overlap)",
                chunks.size(), pagesPerChunk, overlapPages);

        // ── Build map sub-workflow: Agent 1 → 2 → 3 ────────────────────
        UntypedAgent mapWorkflow = agentFactory.sequence("normalizedCandidates",
                agentFactory.create(CsmExtractionWorkflowConfigV6.CANDIDATE_EXTRACTOR_SPEC),
                agentFactory.create(CsmExtractionWorkflowConfigV6.SOURCE_CLASSIFIER_SPEC),
                agentFactory.create(CsmExtractionWorkflowConfigV6.NAME_NORMALIZER_SPEC));

        // ── Run per chunk: overwrite → invoke → collect ─────────────────
        List<String> chunkResultEntries = new ArrayList<>();

        for (ChunkContext chunk : chunks) {
            log.info("MAP — processing chunk {}/{}: pages {}-{}",
                    chunk.chunkIndex() + 1, chunks.size(),
                    chunk.pageStart(), chunk.pageEnd());

            // OVERWRITE sourceText with chunk text
            scope.writeState("sourceText", chunk.chunkText());

            // Invoke agents 1-3 on this chunk
            mapWorkflow.invoke(scope);

            // COLLECT outputs from scope (agent outputs are strings)
            String rawNames = readString(scope, "rawNames");
            String sourceClassification = readString(scope, "sourceClassification");
            String normalizedCandidates = readString(scope, "normalizedCandidates");

            // Build chunk result JSON entry
            String chunkEntry = buildChunkEntryJson(chunk,
                    rawNames, sourceClassification, normalizedCandidates);
            chunkResultEntries.add(chunkEntry);

            log.info("MAP — chunk {}/{} complete", chunk.chunkIndex() + 1, chunks.size());
        }

        // ── Restore original sourceText ─────────────────────────────────
        scope.writeState("sourceText", originalSourceText);

        // ── Write aggregated chunk results ──────────────────────────────
        String chunkResultsJson = buildChunkResultsJson(chunkResultEntries);
        scope.writeState("chunkResults", chunkResultsJson);

        log.info("MAP phase complete — {} chunks processed, chunkResults written to scope",
                chunks.size());
    }

    // =========================================================================
    //  JSON Builders — string concatenation (no DTO needed)
    // =========================================================================

    /**
     * Builds a single chunk entry JSON. Embeds agent output strings directly.
     */
    private String buildChunkEntryJson(ChunkContext chunk,
                                       String rawNames,
                                       String sourceClassification,
                                       String normalizedCandidates) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"chunkIndex\":").append(chunk.chunkIndex()).append(',');
        sb.append("\"pageStart\":").append(chunk.pageStart()).append(',');
        sb.append("\"pageEnd\":").append(chunk.pageEnd()).append(',');
        sb.append("\"overlapStartPage\":").append(chunk.overlapStartPage()).append(',');
        sb.append("\"overlapEndPage\":").append(chunk.overlapEndPage()).append(',');
        sb.append("\"isFirstChunk\":").append(chunk.isFirstChunk()).append(',');
        sb.append("\"isLastChunk\":").append(chunk.isLastChunk()).append(',');
        // Embed agent outputs directly — they are already valid JSON strings
        sb.append("\"rawNames\":").append(ensureValidJson(rawNames)).append(',');
        sb.append("\"sourceClassification\":").append(ensureValidJson(sourceClassification)).append(',');
        sb.append("\"normalizedCandidates\":").append(ensureValidJson(normalizedCandidates));
        sb.append('}');
        return sb.toString();
    }

    /**
     * Wraps all chunk entries into the top-level chunkResults structure.
     */
    private String buildChunkResultsJson(List<String> chunkEntries) {
        StringBuilder sb = new StringBuilder("{\"chunks\":[");
        for (int i = 0; i < chunkEntries.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(chunkEntries.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * Ensures a string is valid JSON. If null or blank, returns empty object.
     */
    private String ensureValidJson(String json) {
        if (json == null || json.isBlank()) return "{}";
        String trimmed = json.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed;
        // Wrap non-JSON strings as a JSON string value
        try {
            return objectMapper.writeValueAsString(trimmed);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Reads a string from scope, handling null and non-string values.
     */
    private String readString(AgenticScope scope, String key) {
        Object value = scope.readState(key);
        return value != null ? value.toString() : "";
    }
}
