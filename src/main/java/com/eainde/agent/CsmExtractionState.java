package com.db.cob.ms.documentai.agent.state;

import dev.langchain4j.agent.graph.AgentState;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.*;

@Data
@EqualsAndHashCode(callSuper = true)
public class CsmExtractionState extends AgentState {

    // Input state
    public static final String REQUEST_DTO = "requestDto";
    public static final String FILE_RECORDS = "fileRecords";

    // Processing state
    public static final String CURRENT_FILE_INDEX = "currentFileIndex";
    public static final String CURRENT_BATCH_START = "currentBatchStart";
    public static final String BATCH_SIZE = "batchSize";

    // Intermediate results
    public static final String RAW_EXTRACTIONS = "rawExtractions";
    public static final String NORMALIZED_RECORDS = "normalizedRecords";
    public static final String VALIDATION_RESULTS = "validationResults";
    public static final String CONFLICT_RESOLUTIONS = "conflictResolutions";

    // Final output
    public static final String FINAL_RECORDS = "finalRecords";
    public static final String ERRORS = "errors";
    public static final String STATUS = "status";
    public static final String METADATA = "metadata";

    // Retry/error handling
    public static final String RETRY_COUNT = "retryCount";
    public static final String FAILED_BATCHES = "failedBatches";

    public CsmExtractionState(Map<String, Object> data) {
        super(data);
    }

    public static CsmExtractionState create(AiWorkflowRequestDTO request,
                                            List<FileRecord> files) {
        Map<String, Object> data = new HashMap<>();
        data.put(REQUEST_DTO, request);
        data.put(FILE_RECORDS, files);
        data.put(CURRENT_FILE_INDEX, 0);
        data.put(CURRENT_BATCH_START, 1);
        data.put(BATCH_SIZE, 100);
        data.put(RAW_EXTRACTIONS, new ArrayList<RawExtraction>());
        data.put(ERRORS, new ArrayList<String>());
        data.put(RETRY_COUNT, 0);
        data.put(METADATA, new ExtractionMetadata());
        return new CsmExtractionState(data);
    }

    // Helper methods for type-safe access
    public List<FileRecord> getFileRecords() {
        return (List<FileRecord>) data().get(FILE_RECORDS);
    }

    public void addRawExtraction(RawExtraction extraction) {
        List<RawExtraction> extractions = (List<RawExtraction>) data().get(RAW_EXTRACTIONS);
        extractions.add(extraction);
    }

    public FileRecord getCurrentFile() {
        List<FileRecord> files = getFileRecords();
        int index = (Integer) data().get(CURRENT_FILE_INDEX);
        return files.get(index);
    }

    public boolean hasMoreFiles() {
        int index = (Integer) data().get(CURRENT_FILE_INDEX);
        return index < getFileRecords().size();
    }

    public void moveToNextFile() {
        int index = (Integer) data().get(CURRENT_FILE_INDEX);
        data().put(CURRENT_FILE_INDEX, index + 1);
        data().put(CURRENT_BATCH_START, 1); // Reset batch counter
    }

    public boolean hasMoreBatches(int totalRecordsInFile) {
        int start = (Integer) data().get(CURRENT_BATCH_START);
        return start <= totalRecordsInFile;
    }

    public void moveToNextBatch() {
        int start = (Integer) data().get(CURRENT_BATCH_START);
        int batchSize = (Integer) data().get(BATCH_SIZE);
        data().put(CURRENT_BATCH_START, start + batchSize);
    }
}