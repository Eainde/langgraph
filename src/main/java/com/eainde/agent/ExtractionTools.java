package com.eainde.agent;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExtractionTools {

    // Dependencies from your original code
    private final GeminiClient geminiClient;
    private final AiChatPromptRepo promptRepo;
    private final GcsStorageHandler gcsStorage;
    private final CsmDtoConverter dtoConverter; // Helper to parse JSON strings

    // --- TOOL 1: THE EXTRACTOR (Replaces the API Call logic) ---

    @Tool("Fetches a batch of extracted records from the document using pagination.")
    public ExtractionResponse extractBatch(
            @P("The unique key of the file to process") String fileKey,
            @P("The starting index for extraction (e.g., 1)") int start,
            @P("The ending index for extraction (e.g., 100)") int end
    ) {
        try {
            // 1. Load Resources (Mimics your setup logic)
            // Ideally cached or loaded once per request, but fine here for the tool
            AiChatPrompt promptMeta = promptRepo.findById("CSM_LIST_EXTRACTION")
                    .orElseThrow(() -> new IllegalStateException("Prompt not found"));
            PromptConfig config = PromptConfig.of(promptMeta);
            MediaResource media = gcsStorage.fetchFile(fileKey);

            // 2. Prepare Prompt (Logic from your loop)
            String promptText = config.getPromptText()
                    .replace("${start}", String.valueOf(start))
                    .replace("${end}", String.valueOf(end))
                    .replace("${file_name}", media.getName());

            // 3. Call Gemini
            GeminiClientResponseDTO response = geminiClient.callAiModel(promptMeta, promptText, config, media);

            // 4. Return Raw Response for the Agent to Analyze
            // The Agent will look at 'finishReason' to decide what to do next.
            return new ExtractionResponse(
                    response.getResponseText(),
                    response.getFinishReason(), // e.g., "STOP", "MAX_TOKENS", "REASON_NO_DATA"
                    media.getName()
            );

        } catch (Exception e) {
            log.error("Extraction failed for file {}", fileKey, e);
            return new ExtractionResponse(null, "ERROR", fileKey);
        }
    }

    // --- TOOL 2: THE REPAIRER (Replaces 'case REASON_MAX_TOKENS') ---

    @Tool("Repairs a broken or truncated JSON string when the extraction status is 'MAX_TOKENS'.")
    public String repairBrokenJson(
            @P("The partial JSON string returned by the extraction tool") String brokenJson
    ) {
        // Logic explicitly copied from your 'restorePartialResponse' screenshot
        if (!StringUtils.hasText(brokenJson)) {
            return "{\"extracted_records\": []}";
        }

        int pos = brokenJson.lastIndexOf("},");
        if (pos == -1) {
            // Fallback if we can't find a clean break point
            return brokenJson;
        }

        // Closes the JSON array and object properly
        return brokenJson.substring(0, pos) + "}]}";
    }

    // --- TOOL 3: THE PROCESSOR (Replaces 'default' / Success case) ---

    @Tool("Validates and processes a JSON string. Returns a summary of records found.")
    public BatchProcessingResult processBatchResult(
            @P("The valid JSON content to process") String jsonContent,
            @P("The current finish reason (e.g., STOP or REPAIRED)") String status
    ) {
        // Replaces the 'case REASON_NO_DATA' logic
        if ("REASON_NO_DATA".equals(status)) {
            return new BatchProcessingResult(0, false, "No data found in this batch.");
        }

        try {
            // Replaces 'convertJsonToObject' and 'allRecords.addAll' logic
            CSMExtractionContentDTO dto = dtoConverter.fromJson(jsonContent, CSMExtractionContentDTO.class);
            List<CSMExtractedRecordDTO> records = dto.getExtractedRecords();

            if (records == null || records.isEmpty()) {
                return new BatchProcessingResult(0, false, "JSON was valid but contained no records.");
            }

            // In a real agent, you might save these to a DB or ThreadLocal state here.
            // For now, we return them so the Agent can add them to its memory.
            return new BatchProcessingResult(
                    records.size(),
                    true, // hasData
                    "Successfully processed " + records.size() + " records."
            );

        } catch (Exception e) {
            log.error("JSON parsing failed", e);
            return new BatchProcessingResult(0, false, "Failed to parse JSON: " + e.getMessage());
        }
    }

    // --- HELPER DTOs (The Language the Agent speaks) ---

    // The Agent reads this to know if it hit a token limit
    public record ExtractionResponse(
            String rawJson,
            String finishReason,
            String fileName
    ) {}

    // The Agent reads this to know if it should continue looping
    public record BatchProcessingResult(
            int count,
            boolean hasData,
            String message
    ) {}
}
