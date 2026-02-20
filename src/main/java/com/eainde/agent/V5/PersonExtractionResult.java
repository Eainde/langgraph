package com.eainde.agent.V5;

package com.db.clm.kyc.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Output of the person-extractor agent (Phase 2).
 *
 * <p>Contains all natural person candidates and any NNP entities found
 * in governance roles. Ordered by J4 traversal (document → page → reading order).</p>
 *
 * @param rawCandidates  all extracted natural persons
 * @param nnpCandidates  non-natural-person entities in governance roles
 */
public record PersonExtractionResult(
        @JsonProperty("raw_candidates") List<RawCandidate> rawCandidates,
        @JsonProperty("nnp_candidates") List<NnpCandidate> nnpCandidates
) {}
