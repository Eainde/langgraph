package com.eainde.agent.V5;

package com.db.clm.kyc.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * A person candidate with CSM governance classification applied.
 *
 * <p>Produced by the csm-classifier agent (Phase 3). Extends the raw candidate
 * data with isCsm determination, governance tags, and country profile information.</p>
 *
 * @param id                    sequential integer
 * @param firstName             Latin ASCII, Title Case
 * @param middleName            Latin ASCII or null
 * @param lastName              Latin ASCII, Title Case
 * @param personalTitle         as source or null
 * @param jobTitle              as source (governance only) or null
 * @param documentName          prevailing source document
 * @param pageNumber            1-based page reference
 * @param isCsm                 CSM eligibility determination
 * @param governanceBasis       canonical fragment explaining the determination
 * @param sourceClassTag        "H1"–"H4"
 * @param recencyTag            "R: YYYY-MM-DD" or "R: undated"
 * @param conflictTag           "C: clear", "C: resolved", or "C: unresolved"
 * @param scopeTag              scope qualifier or null (e.g., "S: branch-ho")
 * @param iboTag                IBO trail result or null (e.g., "IBO: present")
 * @param currencyTag           "U: stale", "U: undated", or null
 * @param countryProfileApplied country profile code or null (e.g., "CP DE")
 * @param signatoryCalibration  signatory status note or null
 * @param attributeGaps         missing attributes per QG7, or null
 */
public record ClassifiedCandidate(
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
        @JsonProperty("attributeGaps")         List<String> attributeGaps
) {}
