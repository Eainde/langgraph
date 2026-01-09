package com.eainde.agent;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class ExtractionTools {

    private final GeminiClient geminiClient;
    private final GcsStorageHandler gcsStorage;

    @Tool("Extracts a batch of CSM records from the document using start and end indices.")
    public ExtractionResponse extractRecords(
            @P("The file name/key to process") String fileKey,
            @P("The starting ID for this batch (e.g., 1)") int start,
            @P("The ending ID for this batch (e.g., 100)") int end
    ) {
        // Preserves your logic for fetching and calling the model
        return geminiClient.extract(fileKey, start, end);
    }

    @Tool("Repairs JSON that was cut off due to token limits. Use this if finishReason is 'MAX_TOKENS'.")
    public String repairJson(
            @P("The truncated JSON string") String partialJson
    ) {
        // Logic from your screenshot: finds last '},' and closes with '}]}'
        if (partialJson == null || !partialJson.contains("},")) return "{\"extracted_records\": []}";
        int pos = partialJson.lastIndexOf("},");
        return partialJson.substring(0, pos) + "}]}";
    }
}
