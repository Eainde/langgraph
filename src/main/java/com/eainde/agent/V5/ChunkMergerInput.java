package com.eainde.agent.V5;

package com.db.clm.kyc.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Input to the chunk-merger agent.
 *
 * <p>Aggregates the outputs from all chunks (agents 1-2 per chunk)
 * into a single structure for cross-chunk de-duplication and source merging.</p>
 *
 * @param chunks list of per-chunk extraction results
 */
public record ChunkMergerInput(
        @JsonProperty("chunks") List<ChunkExtractionResult> chunks
) {}