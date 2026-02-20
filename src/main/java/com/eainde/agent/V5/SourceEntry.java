package com.eainde.agent.V5;

package com.db.clm.kyc.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single classified source document entry.
 *
 * <p>Produced by the source-classifier agent (Phase 1) for each document
 * in the input set, ranked by IDandV hierarchy and recency.</p>
 *
 * @param documentName  exact title or filename as provided
 * @param sourceClass   hierarchy tag: "H1", "H2", "H3", or "H4"
 * @param documentType  classification (e.g., "Commercial Registry Extract", "Articles of Association")
 * @param documentDate  ISO date "YYYY-MM-DD" if present, else null
 * @param currencyTag   "current", "U: stale", or "U: undated"
 * @param currencyNote  brief explanation if stale/undated, else null
 * @param pageCount     page count if determinable, else null
 * @param admissionRank 1-based rank by hierarchy then recency (1 = highest authority)
 */
public record SourceEntry(
        @JsonProperty("documentName")  String documentName,
        @JsonProperty("sourceClass")   String sourceClass,
        @JsonProperty("documentType")  String documentType,
        @JsonProperty("documentDate")  String documentDate,
        @JsonProperty("currencyTag")   String currencyTag,
        @JsonProperty("currencyNote")  String currencyNote,
        @JsonProperty("pageCount")     Integer pageCount,
        @JsonProperty("admissionRank") int admissionRank
) {}
