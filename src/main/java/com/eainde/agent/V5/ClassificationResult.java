package com.eainde.agent.V5;

package com.db.clm.kyc.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Output of the csm-classifier agent (Phase 3).
 *
 * <p>Contains all candidates with CSM eligibility determinations applied.
 * Each candidate has isCsm, governance basis, source/conflict/scope tags,
 * and country profile information.</p>
 *
 * @param classifiedCandidates all candidates with governance classification
 */
public record ClassificationResult(
        @JsonProperty("classified_candidates") List<ClassifiedCandidate> classifiedCandidates
) {}
