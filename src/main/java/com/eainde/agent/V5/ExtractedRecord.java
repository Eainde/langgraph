package com.eainde.agent.V5;

package com.db.clm.kyc.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single record in the final extraction output, conforming to the
 * CSM extraction JSON schema (Section J1).
 *
 * <p>This is the canonical output format — each record represents one
 * unique natural person with their CSM determination and assembled reason.</p>
 *
 * <p>Required fields: id, firstName, lastName, pageNumber, reason, isCsm.
 * Types: id/pageNumber = int; isCsm = boolean; others = String.
 * middleName may be null (never empty string).</p>
 *
 * @param id            sequential integer per J4 ordering
 * @param firstName     Latin ASCII, Title Case
 * @param middleName    Latin ASCII or null (never empty string)
 * @param lastName      Latin ASCII, Title Case
 * @param personalTitle as source or null
 * @param jobTitle      as source (governance only) or null
 * @param documentName  prevailing source document
 * @param pageNumber    1-based page reference
 * @param reason        assembled reason string per R2 canonical order
 * @param isCsm         final CSM eligibility determination
 */
public record ExtractedRecord(
        @JsonProperty("id")            int id,
        @JsonProperty("firstName")     String firstName,
        @JsonProperty("middleName")    String middleName,
        @JsonProperty("lastName")      String lastName,
        @JsonProperty("personalTitle") String personalTitle,
        @JsonProperty("jobTitle")      String jobTitle,
        @JsonProperty("documentName")  String documentName,
        @JsonProperty("pageNumber")    int pageNumber,
        @JsonProperty("reason")        String reason,
        @JsonProperty("isCsm")        boolean isCsm
) {}
