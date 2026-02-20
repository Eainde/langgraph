package com.eainde.agent.V5;

package com.db.clm.kyc.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Statistics from the chunk-merger agent's de-duplication pass.
 *
 * @param totalChunks                number of chunks processed
 * @param totalCandidatesBeforeMerge sum of candidates across all chunks (pre-dedup)
 * @param totalCandidatesAfterMerge  unique candidates after de-duplication
 * @param duplicatesRemoved          total duplicates removed
 * @param overlapDuplicates          duplicates specifically from overlap zones
 */
public record MergeStats(
        @JsonProperty("totalChunks")                int totalChunks,
        @JsonProperty("totalCandidatesBeforeMerge") int totalCandidatesBeforeMerge,
        @JsonProperty("totalCandidatesAfterMerge")  int totalCandidatesAfterMerge,
        @JsonProperty("duplicatesRemoved")          int duplicatesRemoved,
        @JsonProperty("overlapDuplicates")          int overlapDuplicates
) {}
