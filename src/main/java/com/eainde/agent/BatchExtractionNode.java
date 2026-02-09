package com.db.cob.ms.documentai.agent.node;

import dev.langchain4j.agent.graph.AsyncNodeAction;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Node 1: Batch Extraction (Deterministic orchestration + Cognitive extraction)
 */
@Slf4j
@Component
public class BatchExtractionNode implements AsyncNodeAction<CsmExtractionState> {

    private final ChatLanguageModel chatModel;
    private final CsmExtractionTools tools;

    @Override
    public CompletableFuture<Map<String, Object>> apply(CsmExtractionState state) {

        return CompletableFuture.supplyAsync(() -> {
            List<RawExtraction> allExtractions = new ArrayList<>();

            // DETERMINISTIC: Java manages file iteration
            while (state.hasMoreFiles()) {
                FileRecord currentFile = state.getCurrentFile();
                log.info("Processing file: {}", currentFile.fileName());

                // Extract all batches from this file
                List<RawExtraction> fileExtractions = extractAllBatchesFromFile(
                        state, currentFile
                );
                allExtractions.addAll(fileExtractions);

                state.moveToNextFile();
            }

            // Update state
            return Map.of(
                    CsmExtractionState.RAW_EXTRACTIONS, allExtractions,
                    CsmExtractionState.STATUS, "EXTRACTION_COMPLETE",
                    "extractionCount", allExtractions.size()
            );
        });
    }

    private List<RawExtraction> extractAllBatchesFromFile(
            CsmExtractionState state,
            FileRecord file
    ) {
        List<RawExtraction> extractions = new ArrayList<>();

        // Create agent for this file
        CsmExtractionAgent agent = AiServices.builder(CsmExtractionAgent.class)
                .chatLanguageModel(chatModel)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(5)) // Short memory
                .build();

        // DETERMINISTIC: Java manages batch iteration
        int batchSize = state.getBatchSize();
        int start = 1;
        int maxRecords = estimateRecordsInFile(file); // or use a sentinel
        int retries = 0;
        int maxRetries = 3;

        while (start <= maxRecords && retries < maxRetries) {
            int end = start + batchSize - 1;

            try {
                log.debug("Extracting batch: file={}, range=[{}-{}]",
                        file.fileName(), start, end);

                // COGNITIVE: LLM extracts this batch
                String jsonResult = agent.extractBatch(
                        file.fileName(),
                        file.documentType(),
                        start,
                        end,
                        file.content()
                );

                // Parse and validate
                BatchResult batch = parseBatchResult(jsonResult);

                if (batch.isEmpty()) {
                    log.info("No more records found at position {}", start);
                    break; // Done with this file
                }

                // Add to results
                extractions.addAll(batch.toRawExtractions(file));

                // Move to next batch
                start = end + 1;
                retries = 0; // Reset retry counter on success

            } catch (Exception e) {
                log.error("Batch extraction failed [{}:{}], retry {}/{}",
                        start, end, retries + 1, maxRetries, e);

                retries++;
                if (retries >= maxRetries) {
                    // Record failure and move on
                    state.addError(String.format(
                            "Failed to extract batch [%d:%d] after %d retries",
                            start, end, maxRetries
                    ));
                    start = end + 1; // Skip this batch
                    retries = 0;
                }

                // Exponential backoff
                sleepExponentially(retries);
            }
        }

        return extractions;
    }

    private BatchResult parseBatchResult(String json) {
        // Parse JSON, validate structure, convert to domain objects
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode root = mapper.readTree(cleanJson(json));
            JsonNode records = root.get("extractedRecords");

            if (records == null || !records.isArray()) {
                return BatchResult.empty();
            }

            List<ExtractedRecord> parsed = new ArrayList<>();
            for (JsonNode record : records) {
                parsed.add(mapper.treeToValue(record, ExtractedRecord.class));
            }

            return new BatchResult(parsed);

        } catch (JsonProcessingException e) {
            throw new ExtractionException("Failed to parse extraction result", e);
        }
    }

    private String cleanJson(String json) {
        // Remove markdown fences, trim, etc.
        return json.replace("```json", "")
                .replace("```", "")
                .trim();
    }
}

/**
 * Node 2: Normalization (Cognitive - handles ambiguity)
 */
@Slf4j
@Component
public class NormalizationNode implements AsyncNodeAction<CsmExtractionState> {

    private final ChatLanguageModel chatModel;

    @Override
    public CompletableFuture<Map<String, Object>> apply(CsmExtractionState state) {

        return CompletableFuture.supplyAsync(() -> {
            List<RawExtraction> rawExtractions =
                    (List<RawExtraction>) state.data().get(CsmExtractionState.RAW_EXTRACTIONS);

            if (rawExtractions.isEmpty()) {
                return Map.of(
                        CsmExtractionState.NORMALIZED_RECORDS, Collections.emptyList(),
                        CsmExtractionState.STATUS, "NO_RECORDS_TO_NORMALIZE"
                );
            }

            // Create normalization agent
            CsmNormalizationAgent agent = AiServices.builder(CsmNormalizationAgent.class)
                    .chatLanguageModel(chatModel)
                    .build();

            // COGNITIVE: LLM normalizes and de-duplicates
            String rawJson = serializeToJson(rawExtractions);
            String normalizedJson = agent.normalize(rawJson);

            NormalizationResult result = parseNormalizationResult(normalizedJson);

            return Map.of(
                    CsmExtractionState.NORMALIZED_RECORDS, result.normalizedRecords(),
                    "reviewRequired", result.reviewRequired(),
                    CsmExtractionState.STATUS, "NORMALIZATION_COMPLETE"
            );
        });
    }
}

/**
 * Node 3: Validation (Deterministic - applies quality gates)
 */
@Slf4j
@Component
public class ValidationNode implements AsyncNodeAction<CsmExtractionState> {

    private final CsmValidationService validationService;

    @Override
    public CompletableFuture<Map<String, Object>> apply(CsmExtractionState state) {

        return CompletableFuture.supplyAsync(() -> {
            List<NormalizedRecord> records =
                    (List<NormalizedRecord>) state.data().get(CsmExtractionState.NORMALIZED_RECORDS);

            // DETERMINISTIC: Apply QA gates (this is rule-based, not LLM)
            ValidationResult result = validationService.validate(records);

            return Map.of(
                    CsmExtractionState.VALIDATION_RESULTS, result,
                    "passedRecords", result.passedRecords(),
                    "failedGates", result.failedGates(),
                    CsmExtractionState.STATUS, "VALIDATION_COMPLETE"
            );
        });
    }
}

/**
 * Node 4: Conflict Resolution (Cognitive - makes judgment calls)
 */
@Slf4j
@Component
public class ConflictResolutionNode implements AsyncNodeAction<CsmExtractionState> {

    private final ChatLanguageModel chatModel;

    @Override
    public CompletableFuture<Map<String, Object>> apply(CsmExtractionState state) {

        return CompletableFuture.supplyAsync(() -> {
            ValidationResult validationResult =
                    (ValidationResult) state.data().get(CsmExtractionState.VALIDATION_RESULTS);

            List<Conflict> conflicts = validationResult.conflicts();

            if (conflicts.isEmpty()) {
                return Map.of(
                        CsmExtractionState.CONFLICT_RESOLUTIONS, Collections.emptyList(),
                        CsmExtractionState.STATUS, "NO_CONFLICTS"
                );
            }

            // Create resolution agent
            CsmConflictResolutionAgent agent = AiServices.builder(CsmConflictResolutionAgent.class)
                    .chatLanguageModel(chatModel)
                    .build();

            // COGNITIVE: LLM resolves ambiguous cases
            String conflictsJson = serializeToJson(conflicts);
            String resolutionsJson = agent.resolveConflicts(conflictsJson);

            ResolutionResult result = parseResolutionResult(resolutionsJson);

            return Map.of(
                    CsmExtractionState.CONFLICT_RESOLUTIONS, result,
                    CsmExtractionState.STATUS, "CONFLICTS_RESOLVED"
            );
        });
    }
}

/**
 * Node 5: Scoring & Finalization (Deterministic - mathematical formulas)
 */
@Slf4j
@Component
public class ScoringNode implements AsyncNodeAction<CsmExtractionState> {

    private final CsmScoringEngine scoringEngine;

    @Override
    public CompletableFuture<Map<String, Object>> apply(CsmExtractionState state) {

        return CompletableFuture.supplyAsync(() -> {
            ValidationResult validationResult =
                    (ValidationResult) state.data().get(CsmExtractionState.VALIDATION_RESULTS);
            ResolutionResult resolutions =
                    (ResolutionResult) state.data().get(CsmExtractionState.CONFLICT_RESOLUTIONS);

            // DETERMINISTIC: Apply scoring formulas (no LLM needed)
            List<ScoredCsmRecord> scoredRecords = scoringEngine.score(
                    validationResult.passedRecords(),
                    resolutions
            );

            // Apply thresholds
            List<ScoredCsmRecord> finalRecords = scoredRecords.stream()
                    .filter(r -> r.finalScore() >= 0.50) // QG-0 threshold
                    .collect(Collectors.toList());

            return Map.of(
                    CsmExtractionState.FINAL_RECORDS, finalRecords,
                    CsmExtractionState.STATUS, "COMPLETED",
                    "totalRecords", finalRecords.size(),
                    "avgScore", calculateAvgScore(finalRecords)
            );
        });
    }
}