package com.eainde.agent.V5;

package com.db.clm.kyc.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A non-natural-person (NNP) entity found in a governance role.
 *
 * <p>Captured by the person-extractor when an entity (not a natural person)
 * is listed as a board member, signatory, or CSM. Triggers C11/C11' NNP→NP
 * trail in the classifier agent.</p>
 *
 * @param entityName   the entity name as it appears in source
 * @param role         governance role as source text
 * @param documentName source document
 * @param pageNumber   1-based page reference
 */
public record NnpCandidate(
        @JsonProperty("entityName")   String entityName,
        @JsonProperty("role")         String role,
        @JsonProperty("documentName") String documentName,
        @JsonProperty("pageNumber")   int pageNumber
) {}
