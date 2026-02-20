package com.eainde.agent.V5;

package com.db.clm.kyc.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * A classified candidate with an explanatory score and quality gate validations.
 *
 * <p>Produced by the csm-scorer agent (Phase 4). The score is explanatory only —
 * it does NOT override the isCsm determination from Phase 3.</p>
 *
 * @param id                    sequential integer
 * @param firstName             Latin ASCII, Title Case
 * @param middleName            Latin ASCII or null
 * @param lastName              Latin ASCII, Title Case
 * @param personalTitle         as source or null
 * @param jobTitle              as source (governance only) or null
 * @param documentName          prevailing source document
 * @param pageNumber            1-based page reference
 * @param isCsm                 CSM eligibility (from classifier, unchanged)
 * @param governanceBasis       canonical governance fragment (from classifier)
 * @param sourceClassTag        "H1"–"H4"
 * @param recencyTag            "R: YYYY-MM-DD" or "R: undated"
 * @param conflictTag           "C: clear|resolved|unresolved"
 * @param scopeTag              scope qualifier or null
 * @param iboTag                IBO trail result or null
 * @param currencyTag           currency annotation or null
 * @param countryProfileApplied country profile code or null
 * @param signatoryCalibration  signatory status note or null
 * @param attributeGaps         missing attributes per QG7
 * @param score                 final explanatory score [0.00–1.00]
 * @param scoreBreakdown        detailed scoring math
 * @param qualityGateNotes      applicable QG findings
 */
public record ScoredCandidate(
        @JsonProperty("id")                    int id,
        @JsonProperty("firstName")             String firstName,
        @JsonProperty("middleName")            String middleName,
        @JsonProperty("lastName")              String lastName,
        @JsonProperty("personalTitle")         String personalTitle,
        @JsonProperty("jobTitle")              String jobTitle,
        @JsonProperty("documentName")          String documentName,
        @JsonProperty("pageNumber")            int pageNumber,
        @JsonProperty("isCsm")                boolean isCsm,
        @JsonProperty("governanceBasis")       String governanceBasis,
        @JsonProperty("sourceClassTag")        String sourceClassTag,
        @JsonProperty("recencyTag")            String recencyTag,
        @JsonProperty("conflictTag")           String conflictTag,
        @JsonProperty("scopeTag")              String scopeTag,
        @JsonProperty("iboTag")                String iboTag,
        @JsonProperty("currencyTag")           String currencyTag,
        @JsonProperty("countryProfileApplied") String countryProfileApplied,
        @JsonProperty("signatoryCalibration")  String signatoryCalibration,
        @JsonProperty("attributeGaps")         List<String> attributeGaps,
        @JsonProperty("score")                 double score,
        @JsonProperty("scoreBreakdown")        ScoreBreakdown scoreBreakdown,
        @JsonProperty("qualityGateNotes")      List<String> qualityGateNotes
) {}
