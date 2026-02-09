package com.db.cob.ms.documentai.agent.assistant;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Extraction Agent - Understands documents and extracts CSM records
 * This is the COGNITIVE component - LLM does the hard work of understanding
 */
public interface CsmExtractionAgent {

    @SystemMessage("""
        You are a CSM (Client Sensor Manager) extraction specialist.
        
        ROLE: Extract governance/management records from unstructured documents.
        
        STRICT MODE: Be deterministic and follow rules exactly.
        
        KNOWLEDGE BASE:
        - CSM KOS Concepts: Board structures, executive roles, jurisdictions
        - Canonical Titles: CEO, CFO, CRO, COO, CIO, General Counsel, Head of Risk, etc.
        - Exclusions: Non-executive directors (NED), supervisory board (unless dual exec), 
          founders without current authority, advisory board members
        
        EVIDENCE HIERARCHY (for source classification):
        1. Primary internal governance (1.00): ExCo/Board minutes, official org charts
        2. Official regulatory filings (0.85): SEC filings, registry data
        3. Corporate website leadership pages (0.70): Official bios, leadership sections
        4. External secondary sources (0.30): News, analyst reports, industry databases
        """)
    @UserMessage("""
        Extract CSM records from this document batch.
        
        FILE: {{fileName}}
        TYPE: {{documentType}}
        RANGE: Records {{startRecord}} to {{endRecord}}
        
        CONTENT:
        {{fileContent}}
        
        EXTRACTION RULES:
        1. Extract ONLY persons with authority over: strategy, risk, finance, operations
        2. Classify each by canonical title (CEO, CFO, etc.) - use null if ambiguous
        3. Capture effective dates if mentioned
        4. Record the evidence source and classify it (Primary/Official/Corporate/External)
        5. Note any exclusion reasons (NED, supervisory-only, advisory, etc.)
        6. Extract ONLY records in the specified range
        
        OUTPUT FORMAT (valid JSON only, no markdown):
        {
          "extractedRecords": [
            {
              "partyId": "PersonName",
              "personName": "Full Name",
              "canonicalTitle": "CEO|CFO|CRO|...|null",
              "effectiveDate": "YYYY-MM-DD or null",
              "roleEffective": true|false,
              "evidenceSource": "excerpt from document",
              "sourceClass": "PRIMARY|OFFICIAL|CORPORATE|EXTERNAL",
              "baseWeight": 0.3-1.0,
              "exclusionReason": "reason or null"
            }
          ]
        }
        
        CRITICAL:
        - Return ONLY valid JSON
        - No markdown code fences
        - Null for missing data (not empty string)
        - If no records found in range: {"extractedRecords": []}
        """)
    String extractBatch(
            @V("fileName") String fileName,
            @V("documentType") String documentType,
            @V("startRecord") int startRecord,
            @V("endRecord") int endRecord,
            @V("fileContent") String fileContent
    );
}

/**
 * Normalization Agent - Resolves entity ambiguities
 * COGNITIVE: Handles name variations, jurisdiction mapping, de-duplication
 */
public interface CsmNormalizationAgent {

    @SystemMessage("""
        You are a data normalization specialist for CSM records.
        
        ROLE: Resolve ambiguities and unify person identities across records.
        
        TASKS:
        1. Detect name variations (J. Smith vs John Smith vs Jonathan Smith)
        2. Handle title variations (Chief Executive vs CEO)
        3. Resolve jurisdictional ambiguities
        4. Flag conflicts requiring human review
        5. De-duplicate: one canonical record per unique person
        """)
    @UserMessage("""
        Normalize these extracted CSM records.
        
        RAW RECORDS:
        {{rawRecords}}
        
        NORMALIZATION RULES:
        1. Group by person (handle name variations intelligently)
        2. Choose canonical name (most complete, most authoritative source)
        3. Resolve title conflicts (prefer more specific over generic)
        4. If conflicting effective dates → flag for review
        5. Merge evidence from multiple sources for same person
        
        OUTPUT (valid JSON):
        {
          "normalizedRecords": [
            {
              "canonicalId": "unique-person-id",
              "canonicalName": "Full Name",
              "canonicalTitle": "CEO",
              "effectiveDate": "YYYY-MM-DD",
              "roleEffective": true,
              "evidenceSources": ["source1", "source2"],
              "sourceWeights": [1.0, 0.85],
              "nameVariants": ["variant1", "variant2"],
              "conflicts": null or "description"
            }
          ],
          "reviewRequired": [
            {
              "reason": "conflicting-dates",
              "records": [...]
            }
          ]
        }
        """)
    String normalize(@V("rawRecords") String rawRecords);
}

/**
 * Conflict Resolution Agent - Makes judgment calls on ambiguous cases
 * COGNITIVE: Applies expert reasoning to resolve edge cases
 */
public interface CsmConflictResolutionAgent {

    @SystemMessage("""
        You are a governance expert resolving conflicts in CSM classification.
        
        ROLE: Apply expert judgment to ambiguous or conflicting records.
        
        HIERARCHY RULES:
        - Source hierarchy: Primary > Official > Corporate > External
        - Recency: newer > older (but consider superseded vs still-effective)
        - Completeness: more detail > less detail
        - Specificity: exact title > generic description
        """)
    @UserMessage("""
        Resolve conflicts in these CSM records.
        
        CONFLICTS:
        {{conflicts}}
        
        RESOLUTION STRATEGY:
        1. Apply source hierarchy first
        2. If same tier → apply recency
        3. If contradiction unresolved → flag for manual Review
        4. Provide clear rationale for each decision
        
        OUTPUT (valid JSON):
        {
          "resolutions": [
            {
              "recordId": "...",
              "decision": "ACCEPT|REJECT|REVIEW",
              "chosenSource": "...",
              "rationale": "Applied hierarchy rule: official filing (0.85) > website (0.70)",
              "confidence": "HIGH|MEDIUM|LOW"
            }
          ]
        }
        """)
    String resolveConflicts(@V("conflicts") String conflicts);
}