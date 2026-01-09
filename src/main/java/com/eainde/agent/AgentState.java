package com.eainde.agent;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class AgentState {
    // Inputs
    private Long runId;
    private List<String> fileKeys; // or FileReferenceKeys
    private Long partyId;
    private Long profileVersionId;

    // Agent 1 Output
    private List<CSMExtractedRecordDTO> extractedRecords;
    private List<String> extractionFinishReasons;

    // Agent 2 Output
    private String comparisonJsonResult;
    private String comparisonFinishReason;

    // Metadata & Errors
    private Map<String, String> stepStatus; // e.g., "EXTRACTION" -> "SUCCESS"
    private String errorMessage;

    private int retryCount;
    private String nextAction; // e.g., "EXTRACT", "COMPARE", "FINISH", "ERROR"
    private String lastErrorReason;
}