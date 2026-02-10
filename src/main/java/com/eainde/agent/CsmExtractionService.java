package com.eainde.agent;

import com.db.clm.kyc.ai.agents.CsmConsolidationAgent;
import com.db.clm.kyc.ai.agents.CsmExtractionWorkflow;
import com.db.cob.ms.documentai.service.ai_prompt.dto.media.FileRecord;
import com.db.cob.ms.documentai.service.ai_prompt.dto.media.FileReferenceKey;
import com.db.cob.ms.documentai.service.ai_prompt.dto.media.MediaResource;
import com.db.clm.kyc.ai.dto.AiWorkflowRequestKycFilterDTO;
import com.db.clm.kyc.ai.dto.CsmExtractionResult;
import com.db.clm.kyc.ai.handler.DocumentHandler;
import com.db.clm.kyc.ai.extractor.DocumentExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;
import static com.db.clm.kyc.ai.util.NullSafeUtils.nvl;
import static com.db.clm.kyc.ai.constants.ErrorMessages.NO_DOCUMENTS_FOR_EXTRACTION;
import static com.db.clm.kyc.ai.constants.ErrorMessages.NO_MEDIA_RESOURCES_FOR_EXTRACTION;

/**
 * Service that processes multiple files through the CSM Extraction Sequential Workflow.
 *
 * For each FileRecord:
 *   1. Extract/encode the file content from MediaResource
 *   2. Run the 4-agent sequential pipeline (Validate → Extract → Score → Audit)
 *   3. Collect the per-file QA report
 *
 * After all files are processed:
 *   4. Run the ConsolidationAgent to merge all per-file results into a unified CSM list.
 */
@Service
public class CsmExtractionService {

    private static final Logger log = LoggerFactory.getLogger(CsmExtractionService.class);

    private final CsmExtractionWorkflow csmExtractionWorkflow;
    private final CsmConsolidationAgent csmConsolidationAgent;
    private final DocumentExtractor documentExtractor;
    private final DocumentHandler documentHandler;

    public CsmExtractionService(CsmExtractionWorkflow csmExtractionWorkflow,
                                CsmConsolidationAgent csmConsolidationAgent,
                                DocumentExtractor documentExtractor,
                                DocumentHandler documentHandler) {
        this.csmExtractionWorkflow = csmExtractionWorkflow;
        this.csmConsolidationAgent = csmConsolidationAgent;
        this.documentExtractor = documentExtractor;
        this.documentHandler = documentHandler;
    }

    /**
     * Main entry point: fetches all documents for the request,
     * processes each through the CSM pipeline, and returns aggregated results.
     */
    public CsmExtractionResult processRequest(AiWorkflowRequestKycFilterDTO requestDTO) {
        log.info("Starting CSM extraction for partyId: {}", requestDTO.getPartyId());

        List<FileRecord> documents = getDocuments(requestDTO);
        if (documents.isEmpty()) {
            log.warn("No documents found for partyId: {}", requestDTO.getPartyId());
            return CsmExtractionResult.empty(requestDTO.getPartyId());
        }

        log.info("Found {} documents to process for partyId: {}", documents.size(), requestDTO.getPartyId());

        // Step 1: Process each file through the 4-agent sequential workflow
        List<CsmExtractionResult.FileResult> fileResults = new ArrayList<>();

        for (int i = 0; i < documents.size(); i++) {
            FileRecord fileRecord = documents.get(i);
            String fileName = resolveFileName(fileRecord);
            log.info("Processing file {}/{}: {}", i + 1, documents.size(), fileName);

            try {
                String qaReport = processFile(fileRecord);
                fileResults.add(CsmExtractionResult.FileResult.success(
                        fileName,
                        fileRecord.key().sourceFileKey(),
                        qaReport
                ));
                log.info("Successfully processed file: {}", fileName);
            } catch (Exception e) {
                log.error("Failed to process file: {}", fileName, e);
                fileResults.add(CsmExtractionResult.FileResult.failure(
                        fileName,
                        fileRecord.key().sourceFileKey(),
                        e.getMessage()
                ));
            }
        }

        CsmExtractionResult aggregatedResult = CsmExtractionResult.aggregate(
                requestDTO.getPartyId(), fileResults
        );

        // Step 2: Run the ConsolidationAgent to merge all per-file QA reports
        if (aggregatedResult.getSuccessCount() > 0) {
            try {
                String allQaReportsCombined = aggregatedResult.getAllQaReports().stream()
                        .collect(Collectors.joining("\n\n---FILE BOUNDARY---\n\n"));

                String consolidatedReport = csmConsolidationAgent.consolidate(allQaReportsCombined);
                aggregatedResult.setConsolidatedReport(consolidatedReport);

                log.info("Consolidation completed. Merged {} file reports into unified CSM list.",
                        aggregatedResult.getSuccessCount());
            } catch (Exception e) {
                log.error("Consolidation agent failed for partyId: {}", requestDTO.getPartyId(), e);
                aggregatedResult.setConsolidationError(e.getMessage());
            }
        }

        log.info("CSM extraction completed for partyId: {}. Processed: {}, Succeeded: {}, Failed: {}",
                requestDTO.getPartyId(),
                fileResults.size(),
                aggregatedResult.getSuccessCount(),
                aggregatedResult.getFailureCount());

        return aggregatedResult;
    }

    /**
     * Processes a single FileRecord through the 4-agent sequential workflow.
     * Builds a structured input string containing file metadata + content
     * so the LLM can perform extraction directly from the raw file.
     */
    private String processFile(FileRecord fileRecord) {
        String fileContent = prepareFileContent(fileRecord);
        String fileName = resolveFileName(fileRecord);
        String mimeType = resolveMimeType(fileRecord);

        if (fileContent == null || fileContent.isBlank()) {
            throw new IllegalArgumentException("No content could be extracted from file: " + fileName);
        }

        // Build the source input with file metadata so the LLM has full context
        String sourceInput = buildSourceInput(fileName, mimeType, fileContent);

        // Run the sequential workflow: Validate → Extract → Score → Audit
        return csmExtractionWorkflow.extractCsm(sourceInput);
    }

    /**
     * Prepares file content for LLM consumption based on the content type.
     * Text-based files are converted to String directly.
     * Binary files (PDF, DOCX, etc.) are base64-encoded for the LLM to process.
     */
    private String prepareFileContent(FileRecord fileRecord) {
        MediaResource media = fileRecord.media();
        byte[] content = media.content();

        if (content == null || content.length == 0) {
            return null;
        }

        String mimeType = resolveMimeType(fileRecord);

        // Text-based formats: convert bytes to string directly
        if (isTextBased(mimeType)) {
            return new String(content, StandardCharsets.UTF_8);
        }

        // Binary formats (PDF, DOCX, XLS, etc.): base64 encode
        // The LLM (with multimodal/document support) will handle extraction
        return Base64.getEncoder().encodeToString(content);
    }

    /**
     * Builds a structured source input string with file metadata + content.
     */
    private String buildSourceInput(String fileName, String mimeType, String fileContent) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== SOURCE DOCUMENT METADATA ===\n");
        sb.append("FileName: ").append(fileName).append("\n");
        sb.append("MimeType: ").append(mimeType).append("\n");
        sb.append("ContentEncoding: ").append(isTextBased(mimeType) ? "TEXT" : "BASE64").append("\n");
        sb.append("\n=== SOURCE DOCUMENT CONTENT ===\n");
        sb.append(fileContent);
        return sb.toString();
    }

    /**
     * Resolves the file name from FileRecord, preferring key.fileName() over media.name().
     */
    private String resolveFileName(FileRecord fileRecord) {
        String name = fileRecord.key().fileName();
        if (name == null || name.isBlank()) {
            name = fileRecord.media().name();
        }
        return name != null ? name : "unknown";
    }

    /**
     * Resolves the MIME type string from FileRecord.
     */
    private String resolveMimeType(FileRecord fileRecord) {
        if (fileRecord.key().mimeType() != null) {
            return fileRecord.key().mimeType().type() + "/" + fileRecord.key().mimeType().subtype();
        }
        if (fileRecord.media().mimeType() != null) {
            return fileRecord.media().mimeType().type() + "/" + fileRecord.media().mimeType().subtype();
        }
        return "application/octet-stream";
    }

    /**
     * Checks if the MIME type represents a text-based format.
     */
    private boolean isTextBased(String mimeType) {
        if (mimeType == null) return false;
        return mimeType.startsWith("text/")
                || mimeType.contains("csv")
                || mimeType.contains("json")
                || mimeType.contains("xml")
                || mimeType.contains("html");
    }

    /**
     * Fetches documents for CSM identification (from your existing code).
     */
    private List<FileRecord> getDocuments(AiWorkflowRequestKycFilterDTO requestDTO) {
        try {
            List<FileReferenceKey> fileRefKeys =
                    nvl(documentExtractor.getFilesRefKeyForCSMIdentification(requestDTO), emptyList());
            documentHandler.validateFileKeyList(fileRefKeys, NO_DOCUMENTS_FOR_EXTRACTION);
            return documentHandler.getFileRecords(fileRefKeys, requestDTO.getPartyId(), NO_MEDIA_RESOURCES_FOR_EXTRACTION);
        } catch (Exception e) {
            log.error("Failed to parse file reference keys in processBatchResult", e);
            return Collections.emptyList();
        }
    }
}
