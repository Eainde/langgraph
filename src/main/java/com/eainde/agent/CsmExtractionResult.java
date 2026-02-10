package com.eainde.agent;

import java.util.List;

/**
 * Aggregated result from processing multiple files through the CSM extraction pipeline.
 *
 * Contains per-file results (each with its own QA report) and summary statistics.
 */
public class CsmExtractionResult {

    private final String partyId;
    private final List<FileResult> fileResults;
    private final int successCount;
    private final int failureCount;
    private String consolidatedReport;
    private String consolidationError;

    private CsmExtractionResult(String partyId, List<FileResult> fileResults) {
        this.partyId = partyId;
        this.fileResults = fileResults;
        this.successCount = (int) fileResults.stream().filter(FileResult::isSuccess).count();
        this.failureCount = fileResults.size() - this.successCount;
    }

    public static CsmExtractionResult aggregate(String partyId, List<FileResult> fileResults) {
        return new CsmExtractionResult(partyId, fileResults);
    }

    public static CsmExtractionResult empty(String partyId) {
        return new CsmExtractionResult(partyId, List.of());
    }

    public String getPartyId() {
        return partyId;
    }

    public List<FileResult> getFileResults() {
        return fileResults;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public int getTotalCount() {
        return fileResults.size();
    }

    public String getConsolidatedReport() {
        return consolidatedReport;
    }

    public void setConsolidatedReport(String consolidatedReport) {
        this.consolidatedReport = consolidatedReport;
    }

    public String getConsolidationError() {
        return consolidationError;
    }

    public void setConsolidationError(String consolidationError) {
        this.consolidationError = consolidationError;
    }

    /**
     * Returns only the successful QA reports from all files.
     */
    public List<String> getAllQaReports() {
        return fileResults.stream()
                .filter(FileResult::isSuccess)
                .map(FileResult::getQaReport)
                .toList();
    }

    /**
     * Per-file result containing either a QA report (success) or error message (failure).
     */
    public static class FileResult {

        private final String fileName;
        private final String fileReferenceKey;
        private final boolean success;
        private final String qaReport;
        private final String errorMessage;

        private FileResult(String fileName, String fileReferenceKey,
                           boolean success, String qaReport, String errorMessage) {
            this.fileName = fileName;
            this.fileReferenceKey = fileReferenceKey;
            this.success = success;
            this.qaReport = qaReport;
            this.errorMessage = errorMessage;
        }

        public static FileResult success(String fileName, String fileReferenceKey, String qaReport) {
            return new FileResult(fileName, fileReferenceKey, true, qaReport, null);
        }

        public static FileResult failure(String fileName, String fileReferenceKey, String errorMessage) {
            return new FileResult(fileName, fileReferenceKey, false, null, errorMessage);
        }

        public String getFileName() {
            return fileName;
        }

        public String getFileReferenceKey() {
            return fileReferenceKey;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getQaReport() {
            return qaReport;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
