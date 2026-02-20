package com.eainde.agent.V5;

package com.db.clm.kyc.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single person candidate extracted from source documents.
 *
 * <p>Produced by the person-extractor agent (Phase 2). Contains parsed names,
 * titles, de-duplication key, and source linkage. Does NOT contain isCsm
 * or governance classification — those are added by downstream agents.</p>
 *
 * @param id                   sequential integer per J4 traversal order
 * @param firstName            Latin ASCII, Title Case
 * @param middleName           Latin ASCII or null (never empty string)
 * @param lastName             Latin ASCII, Title Case
 * @param personalTitle        as source (honorific only), or null
 * @param jobTitle             as source (governance evidence only), or null
 * @param documentName         prevailing source title/filename
 * @param pageNumber           1-based page reference
 * @param anchorNote           JT/PT notes if no governance anchor found, else null
 * @param normalizationNote    L9 alias/OCR healing notes, else null
 * @param dedupKey             A6.1.5 de-duplication key
 * @param sourceClass          "H1"–"H4" from source classification
 * @param sourceDate           ISO date from source, or null
 * @param temporalStatus       "current", "former", or "unknown"
 * @param formerEffectiveDate  ISO date if former, else null
 * @param signatoryType        "sole", "joint", "none", or "unknown"
 */
public record RawCandidate(
        @JsonProperty("id")                  int id,
        @JsonProperty("firstName")           String firstName,
        @JsonProperty("middleName")          String middleName,
        @JsonProperty("lastName")            String lastName,
        @JsonProperty("personalTitle")       String personalTitle,
        @JsonProperty("jobTitle")            String jobTitle,
        @JsonProperty("documentName")        String documentName,
        @JsonProperty("pageNumber")          int pageNumber,
        @JsonProperty("anchorNote")          String anchorNote,
        @JsonProperty("normalizationNote")   String normalizationNote,
        @JsonProperty("dedupKey")            String dedupKey,
        @JsonProperty("sourceClass")         String sourceClass,
        @JsonProperty("sourceDate")          String sourceDate,
        @JsonProperty("temporalStatus")      String temporalStatus,
        @JsonProperty("formerEffectiveDate") String formerEffectiveDate,
        @JsonProperty("signatoryType")       String signatoryType
) {}