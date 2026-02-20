package com.eainde.agent.V5;

package com.db.clm.kyc.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Detailed scoring breakdown for a single candidate.
 *
 * <p>Captures the positive and negative signals, consensus multiplier,
 * and computed scores per Section D of the extraction specification.</p>
 *
 * @param positiveSignals     list of positive signal descriptions (e.g., "+0.55 Executive Board member")
 * @param negativeSignals     list of negative signal descriptions (e.g., "-0.30 Supervisory only")
 * @param consensusMultiplier multiplier value: 1.00, 0.85, or 0.60
 * @param baseScore           sum of signals clamped to [0.00, 1.00]
 * @param finalScore          baseScore × multiplier, rounded to 2 decimals, clamped [0.00, 1.00]
 */
public record ScoreBreakdown(
        @JsonProperty("positiveSignals")     List<String> positiveSignals,
        @JsonProperty("negativeSignals")     List<String> negativeSignals,
        @JsonProperty("consensusMultiplier") double consensusMultiplier,
        @JsonProperty("baseScore")           double baseScore,
        @JsonProperty("finalScore")          double finalScore
) {}
