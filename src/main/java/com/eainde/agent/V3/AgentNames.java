package com.eainde.agent.V3;

/**
 * Constants for agent names used as keys in the ai_prompt_template table.
 *
 * <p>CSM Extraction pipeline V6 — 12-agent wave-based decomposition:</p>
 * <pre>
 * Wave 1 (parallel):
 *   Agent 1: csm-candidate-extractor   → rawNames
 *   Agent 2: csm-source-classifier     → sourceClassification
 *
 * Wave 2:
 *   Agent 3: csm-name-normalizer       → normalizedCandidates
 *
 * Wave 3:
 *   Agent 4: csm-dedup-linker          → dedupedCandidates
 *
 * Wave 4:
 *   Agent 5: csm-classifier            → classifiedCandidates
 *
 * Wave 5 (parallel):
 *   Agent 6: csm-country-override      → countryOverrides
 *   Agent 7: csm-title-extractor       → titleExtractions
 *   Agent 8: csm-scoring-engine        → scoredCandidates
 *   → Java merge → enrichedCandidates
 *
 * Wave 6:
 *   Agent 9: csm-reason-assembler      → reasonedCandidates
 *
 * Wave 7:
 *   Agent 10: csm-output-formatter     → finalOutput
 *
 * Wave 8 + Loop:
 *   Agent 11: csm-extraction-critic    → extractionReview
 *   Agent 12: csm-output-refiner       → finalOutput (corrected)
 * </pre>
 */
public final class AgentNames {

    private AgentNames() {}

    // ── Wave 1 (parallel) ───────────────────────────────────────────────

    /** Agent 1: Extracts all person names from raw document text. RC1-3. */
    public static final String CANDIDATE_EXTRACTOR = "csm-candidate-extractor";

    /** Agent 2: Ranks and classifies sources using IDandV hierarchy. B, B0-B4. */
    public static final String SOURCE_CLASSIFIER = "csm-source-classifier";

    // ── Wave 2 ──────────────────────────────────────────────────────────

    /** Agent 3: Normalizes names — romanization, OCR healing, aliases. L1-L9. */
    public static final String NAME_NORMALIZER = "csm-name-normalizer";

    // ── Wave 3 ──────────────────────────────────────────────────────────

    /** Agent 4: Deduplicates persons and links to prevailing source. A6, RC4. */
    public static final String DEDUP_LINKER = "csm-dedup-linker";

    // ── Wave 4 ──────────────────────────────────────────────────────────

    /** Agent 5: Universal CSM governance classification. A1-A5, C1-C13 (no CP). */
    public static final String CSM_CLASSIFIER = "csm-classifier";

    // ── Wave 5 (parallel) ───────────────────────────────────────────────

    /** Agent 6: Applies country-specific profile overrides. CP (14 countries). */
    public static final String COUNTRY_OVERRIDE = "csm-country-override";

    /** Agent 7: Extracts jobTitle and personalTitle with ANCHOR GATE. JT, PT. */
    public static final String TITLE_EXTRACTOR = "csm-title-extractor";

    /** Agent 8: Computes explanatory D-scores and validates QG1-QG10. D0-D6, QG. */
    public static final String SCORING_ENGINE = "csm-scoring-engine";

    // ── Wave 6 ──────────────────────────────────────────────────────────

    /** Agent 9: Assembles canonical reason strings in R2 order. R1-R8, B2-B3. */
    public static final String REASON_ASSEMBLER = "csm-reason-assembler";

    // ── Wave 7 ──────────────────────────────────────────────────────────

    /** Agent 10: Formats final JSON output and validates schema. J1-J7, RC8-9, Z. */
    public static final String OUTPUT_FORMATTER = "csm-output-formatter";

    // ── Wave 8 + Critic-Refiner Loop ────────────────────────────────────

    /** Agent 11: Reviews output against compliance checklist. Condensed all sections. */
    public static final String EXTRACTION_CRITIC = "csm-extraction-critic";

    /** Agent 12: Fixes only critic-identified issues. R, J, B2 + feedback. */
    public static final String OUTPUT_REFINER = "csm-output-refiner";

    // ── Map-Reduce (large document handling — separate flow) ────────────

    /** Chunk merger: Deduplicates persons across chunks. Used only in chunked path. */
    public static final String CHUNK_MERGER = "csm-chunk-merger";

    // ── Consolidation (multi-file merge) ────────────────────────────────

    /** Merges multi-file extraction results into unified CSM list. */
    public static final String CONSOLIDATION = "csm-consolidation";
}
