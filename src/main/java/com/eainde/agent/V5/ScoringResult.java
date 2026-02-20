package com.eainde.agent.V5;

package com.db.clm.kyc.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Output of the csm-scorer agent (Phase 4).
 *
 * <p>Contains all candidates with explanatory scores and quality gate validations.
 * Scores do not override isCsm — they are explanatory only (Section D5).</p>
 *
 * @param scoredCandidates all candidates with scores and QG notes
 */
public record ScoringResult(
        @JsonProperty("scored_candidates") List<ScoredCandidate> scoredCandidates
) {}
