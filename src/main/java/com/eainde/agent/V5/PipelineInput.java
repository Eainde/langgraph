package com.eainde.agent.V5;

package com.db.clm.kyc.ai.model;

import dev.langchain4j.data.message.Content;

/**
 * Typed entry point to the CSM extraction pipeline.
 *
 * <p>Wraps the LangChain4j {@link Content} (document text) with
 * the file names metadata. This is what {@code CsmExtractionNode}
 * passes to the pipeline executor.</p>
 *
 * @param sourceContent the document content as LangChain4j Content
 * @param fileNames     comma-separated or JSON list of document names
 */
public record PipelineInput(
        Content sourceContent,
        String fileNames
) {

    /**
     * Convenience: creates a PipelineInput from raw text and file names.
     */
    public static PipelineInput of(Content content, String fileNames) {
        return new PipelineInput(content, fileNames);
    }
}
