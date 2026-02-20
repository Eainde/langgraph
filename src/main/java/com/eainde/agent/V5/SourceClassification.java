package com.eainde.agent.V5;

package com.db.clm.kyc.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Output of the source-classifier agent (Phase 1).
 *
 * <p>Contains all input documents ranked by IDandV hierarchy (H1 > H2 > H3 > H4)
 * and recency. Used by downstream agents to determine prevailing sources.</p>
 *
 * @param sourceClassification ranked list of source entries (admissionRank ascending)
 */
public record SourceClassification(
        @JsonProperty("source_classification") List<SourceEntry> sourceClassification
) {}