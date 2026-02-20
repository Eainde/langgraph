package com.eainde.agent.V5;

package com.db.clm.kyc.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Output of the chunk-merger agent.
 *
 * <p>Contains globally de-duplicated candidates, a unified source classification,
 * and merge statistics. This feeds into agents 3-7 (classifier → output).</p>
 *
 * @param mergedCandidates           de-duplicated person candidates with renumbered ids
 * @param globalSourceClassification unified source ranking across all chunks
 * @param mergeStats                 de-duplication statistics
 */
public record MergedResult(
        @JsonProperty("merged_candidates")            List<RawCandidate> mergedCandidates,
        @JsonProperty("global_source_classification") List<SourceEntry> globalSourceClassification,
        @JsonProperty("merge_stats")                  MergeStats mergeStats
) {}
