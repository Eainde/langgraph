package com.eainde.agent.V5;

package com.db.clm.kyc.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Output of the extraction-critic agent (Phase 6).
 *
 * <p>Contains all identified compliance issues and an overall extraction score.
 * Used by the output-refiner (Phase 7) to fix specific issues. The
 * {@link #extractionScore()} drives the refinement loop exit condition.</p>
 *
 * <p>Score ranges:</p>
 * <ul>
 *   <li>1.00 — No issues found</li>
 *   <li>0.85–0.99 — Minor issues (formatting, tag placement)</li>
 *   <li>0.70–0.84 — Major issues (missing QG notes, reason ordering)</li>
 *   <li>0.50–0.69 — Critical issues (wrong isCsm, missing persons)</li>
 *   <li>&lt;0.50 — Fundamental failures (schema violations, coverage gaps)</li>
 * </ul>
 *
 * @param issues          list of identified compliance issues
 * @param extractionScore overall quality score [0.00–1.00]
 * @param summary         1–2 sentence summary of findings
 */
public record ExtractionReview(
        @JsonProperty("issues")           List<ReviewIssue> issues,
        @JsonProperty("extraction_score") double extractionScore,
        @JsonProperty("summary")          String summary
) {

    /**
     * @return true if no issues were found (perfect score)
     */
    public boolean isClean() {
        return issues == null || issues.isEmpty();
    }

    /**
     * @return count of critical-severity issues
     */
    public long criticalCount() {
        return issues != null
                ? issues.stream().filter(i -> "critical".equalsIgnoreCase(i.severity())).count()
                : 0;
    }

    /**
     * Creates a clean review with no issues.
     */
    public static ExtractionReview clean() {
        return new ExtractionReview(List.of(), 1.00, "No issues found.");
    }
}
