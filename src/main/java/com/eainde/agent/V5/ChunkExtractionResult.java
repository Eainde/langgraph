package com.eainde.agent.V5;

package com.db.clm.kyc.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The combined output of agents 1-2 for a single document chunk.
 *
 * <p>Used as input to the chunk-merger agent which de-duplicates
 * candidates across chunks and merges source classifications.</p>
 *
 * @param chunkIndex           zero-based chunk index
 * @param pageStart            1-based original page where chunk begins
 * @param pageEnd              1-based original page where chunk ends
 * @param overlapStartPage     1-based page where overlap zone begins (-1 for first chunk)
 * @param overlapEndPage       1-based page where overlap zone ends (-1 for first chunk)
 * @param isFirstChunk         true if this is the first chunk
 * @param isLastChunk          true if this is the last chunk
 * @param sourceClassification source classification for this chunk
 * @param rawCandidates        extracted candidates from this chunk
 */
public record ChunkExtractionResult(
        @JsonProperty("chunkIndex")           int chunkIndex,
        @JsonProperty("pageStart")            int pageStart,
        @JsonProperty("pageEnd")              int pageEnd,
        @JsonProperty("overlapStartPage")     int overlapStartPage,
        @JsonProperty("overlapEndPage")       int overlapEndPage,
        @JsonProperty("isFirstChunk")         boolean isFirstChunk,
        @JsonProperty("isLastChunk")          boolean isLastChunk,
        @JsonProperty("sourceClassification") SourceClassification sourceClassification,
        @JsonProperty("rawCandidates")        PersonExtractionResult rawCandidates
) {}
