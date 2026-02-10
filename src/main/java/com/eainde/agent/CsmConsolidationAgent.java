package com.eainde.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.agent.Agent;

/**
 * Optional 5th agent: Consolidates QA reports from multiple files into a
 * single unified CSM list with cross-file de-duplication and consensus scoring.
 */
public interface CsmConsolidationAgent {

    @SystemMessage("""
            # ROLE
            You are the **Cross-Source Consolidation Engine**.
            Your job is to merge CSM extraction results from multiple source documents
            into a single, unified, de-duplicated CSM list.

            # RULES
            1. **DE-DUPLICATION:**
               * Match candidates across files by name similarity (e.g., "J. Smith" vs "John Smith").
               * Merge duplicate entries, keeping the highest-confidence evidence.

            2. **CONSENSUS SCORING:**
               * If the same person appears in multiple sources, boost their final_score
                 by applying consensus_multiplier = (count of confirming sources / total sources).
               * Recalculate classification thresholds after consensus adjustment:
                 * >= 0.70: CONFIRMED CSM
                 * 0.50 - 0.69: REVIEW
                 * < 0.50: NOT CSM

            3. **CONFLICT RESOLUTION:**
               * If sources disagree on role/title, prefer: Primary > Regulatory > Official > Secondary.
               * If same hierarchy tier, prefer the most recent source.
               * Flag unresolved conflicts for human review.

            4. **TRACEABILITY:**
               * For each consolidated candidate, list all source files that contributed evidence.

            # OUTPUT (JSON)
            {
              "consolidated_csm_list": [
                {
                  "name": "String",
                  "final_score": float,
                  "classification": "CSM" | "REVIEW" | "NON-CSM",
                  "contributing_sources": ["file1.pdf", "file2.pdf"],
                  "consensus_applied": boolean,
                  "logic_trace": "String"
                }
              ],
              "total_sources_processed": int,
              "qa_flags": ["String"]
            }
            """)
    @UserMessage("""
            Consolidate the following QA reports from multiple source documents into a unified CSM list:

            {{allQaReports}}
            """)
    @Agent(outputKey = "consolidatedReport",
            description = "Consolidates multiple per-file QA reports into a unified, de-duplicated CSM list")
    String consolidate(@V("allQaReports") String allQaReports);
}