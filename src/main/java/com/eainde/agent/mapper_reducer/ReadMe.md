┌─────────────────────────────────────────────────────────────────────────┐
│                     JAVA ORCHESTRATOR                                   │
│                 (CsmChunkedPipelineExecutor)                            │
│                                                                         │
│  Step 0: needsChunking(sourceText)?                                     │
│          ├── NO → run all 12 agents sequentially (small doc path)       │
│          └── YES ↓                                                      │
│                                                                         │
│  Step 1: DocumentChunker splits into overlapping page ranges            │
│                                                                         │
│     200-page document → 5-page overlap                                  │
│     ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐               │
│     │ Chunk A  │ │ Chunk B  │ │ Chunk C  │ │ Chunk D  │               │
│     │ pp 1-20  │ │ pp 16-35 │ │ pp 31-50 │ │ pp 46-60 │               │
│     └──────────┘ └──────────┘ └──────────┘ └──────────┘               │
└─────────────────────────────────────────────────────────────────────────┘

══════════════════════════════════════════════════════════════════════════
MAP PHASE — per chunk, parallel or sequential
Agents that MUST see raw text
══════════════════════════════════════════════════════════════════════════

Chunk A                  Chunk B                Chunk C              Chunk D
│                        │                      │                    │
▼                        ▼                      ▼                    ▼
┌────────────┐         ┌────────────┐         ┌────────────┐      ┌────────────┐
│ Wave 1     │         │ Wave 1     │         │ Wave 1     │      │ Wave 1     │
│ (parallel) │         │ (parallel) │         │ (parallel) │      │ (parallel) │
│            │         │            │         │            │      │            │
│ Agent 1:   │         │ Agent 1:   │         │ Agent 1:   │      │ Agent 1:   │
│ Candidate  │         │ Candidate  │         │ Candidate  │      │ Candidate  │
│ Extractor  │         │ Extractor  │         │ Extractor  │      │ Extractor  │
│            │         │            │         │            │      │            │
│ Agent 2:   │         │ Agent 2:   │         │ Agent 2:   │      │ Agent 2:   │
│ Source     │         │ Source     │         │ Source     │      │ Source     │
│ Classifier │         │ Classifier │         │ Classifier │      │ Classifier │
└─────┬──────┘         └─────┬──────┘         └─────┬──────┘      └─────┬──────┘
│                      │                      │                    │
▼                      ▼                      ▼                    ▼
┌────────────┐         ┌────────────┐         ┌────────────┐      ┌────────────┐
│ Wave 2     │         │ Wave 2     │         │ Wave 2     │      │ Wave 2     │
│            │         │            │         │            │      │            │
│ Agent 3:   │         │ Agent 3:   │         │ Agent 3:   │      │ Agent 3:   │
│ Language & │         │ Language & │         │ Language & │      │ Language & │
│ Name       │         │ Name       │         │ Name       │      │ Name       │
│ Normalizer │         │ Normalizer │         │ Normalizer │      │ Normalizer │
└─────┬──────┘         └─────┬──────┘         └─────┬──────┘      └─────┬──────┘
│                      │                      │                    │
│  candidates_A        │  candidates_B        │  candidates_C     │  candidates_D
│  sources_A           │  sources_B           │  sources_C        │  sources_D
│                      │                      │                    │
└──────────────┬───────┴──────────────┬───────┘────────────────────┘
│                      │
▼                      ▼

══════════════════════════════════════════════════════════════════════════
MERGE PHASE — cross-chunk dedup (needs global view)
══════════════════════════════════════════════════════════════════════════

              ┌────────────────────────────────────────┐
              │           CHUNK MERGER AGENT            │
              │                                        │
              │  Inputs:                               │
              │   • candidates_A + sources_A            │
              │   • candidates_B + sources_B            │
              │   • candidates_C + sources_C            │
              │   • candidates_D + sources_D            │
              │   • overlap zone metadata per chunk     │
              │                                        │
              │  Does:                                 │
              │   1. Merge source classifications       │
              │      → global ranking (H1>H2>H3>H4)    │
              │   2. Cross-chunk dedup (A6 on           │
              │      NORMALIZED names from Agent 3)     │
              │   3. Overlap duplicate resolution       │
              │      (keep higher-authority record)     │
              │   4. Renumber ids sequentially          │
              │                                        │
              │  Outputs:                              │
              │   • mergedCandidates (deduped)          │
              │   • globalSourceClassification          │
              │   • mergeStats                          │
              └──────────────────┬─────────────────────┘
                                 │
                                 │  global deduped candidates
                                 │  (names already normalized by Agent 3)
                                 ▼

══════════════════════════════════════════════════════════════════════════
REDUCE PHASE — works on structured JSON (no raw text needed)
Optional batching if candidates > 50
══════════════════════════════════════════════════════════════════════════

          ┌─── Java: needsBatching(mergedCandidates)? ───┐
          │                                               │
       NO │                                            YES│
          │                                               │
          ▼                                               ▼
┌───────────────┐                    ┌─────────────────────────────┐
│ Full set      │                    │  Batch 1    Batch 2    ...  │
│ (all at once) │                    │  (50 ppl)   (50 ppl)       │
└───────┬───────┘                    └──────┬──────────┬──────────┘
│                                   │          │
├───────────────────────────────────┘          │
│         (each batch follows same path)       │
▼                                              ▼

    ┌─────────────────────────────────────────────────────────┐
    │  Wave 3: Agent 4 — Dedup + Source Linkage               │
    │          (RC4 prevailing source, A6 final pass)         │
    │                                                         │
    │  NOTE: In chunked path, the chunk merger already did    │
    │  cross-chunk dedup. Agent 4 here does the FINAL pass:   │
    │  within-batch dedup + prevailing source linkage.        │
    │  In the small-doc path, Agent 4 does all dedup.         │
    └──────────────────────┬──────────────────────────────────┘
                           │
                           ▼
    ┌─────────────────────────────────────────────────────────┐
    │  Wave 4: Agent 5 — CSM Classifier                       │
    │          (A1-5, C1-13 — universal governance rules)     │
    │          Per-person operation → safe to batch            │
    └──────────────────────┬──────────────────────────────────┘
                           │
                           ▼
    ┌─────────────────────────────────────────────────────────┐
    │  Wave 5 (parallel — all per-person operations):         │
    │                                                         │
    │  Agent 6: Country Override     Agent 7: Title Extractor │
    │  (CP library — applies         (JT + PT — governance    │
    │   local rules per country)      titles from evidence)   │
    │                                                         │
    │  Agent 8: Scoring Engine                                │
    │  (Section D — computes score from classification)       │
    │                                                         │
    │  All three read classifiedCandidates, write back to it  │
    └──────────────────────┬──────────────────────────────────┘
                           │
                           │  ← If batched: Java merges batch results here
                           │     CandidateBatcher.mergeScoringResultsJson()
                           │     renumbers ids sequentially per J4
                           │
                           ▼

    ┌─────────────────────────────────────────────────────────┐
    │  Wave 6: Agent 9 — Reason Assembler                     │
    │          (R1-R8 canonical order, QG1-10 notes)          │
    │          Needs ALL tags from Agents 5-8                  │
    │          → must run on FULL merged set, not per-batch    │
    └──────────────────────┬──────────────────────────────────┘
                           │
                           ▼
    ┌─────────────────────────────────────────────────────────┐
    │  Wave 7: Agent 10 — Output Formatter & Schema Validator │
    │          (J1-7, Z1-7 — JSON schema, deterministic       │
    │           ordering, output guarantees)                   │
    └──────────────────────┬──────────────────────────────────┘
                           │
                           ▼

══════════════════════════════════════════════════════════════════════════
CRITIC-REFINER LOOP — quality gate
══════════════════════════════════════════════════════════════════════════

    ┌─────────────────────────────────────────────────────────┐
    │  Wave 8: Agent 11 — First Critic                        │
    │          (compliance checklist — bootstraps review)      │
    │          → extractionReview JSON (score + issues)        │
    └──────────────────────┬──────────────────────────────────┘
                           │
                     score >= 0.85?
                      ┌────┴────┐
                   YES│         │NO
                      ▼         ▼
                    DONE    ┌─────────────────────────────────┐
                      │     │  LOOP (max 3 iterations):       │
                      │     │                                 │
                      │     │  Agent 12: Output Refiner       │
                      │     │  (fixes critic-identified       │
                      │     │   issues only)                  │
                      │     │         │                       │
                      │     │         ▼                       │
                      │     │  Agent 11: Loop Critic          │
                      │     │  (re-evaluates)                 │
                      │     │         │                       │
                      │     │   score >= 0.85? ──YES──→ DONE  │
                      │     │         │NO                     │
                      │     │         ▼                       │
                      │     │   next iteration...             │
                      │     └─────────────────────────────────┘
                      │                    │
                      ▼                    ▼
              ┌────────────────────────────────────────┐
              │           FINAL OUTPUT                  │
              │   ExtractionOutput JSON string          │
              │   (caller deserializes to DTO if needed)│
              └────────────────────────────────────────┘


══════════════════════════════════════════════════════════════════════════
BATCHING BOUNDARY — which agents can be batched?
══════════════════════════════════════════════════════════════════════════

┌─────────────────┬───────────────────┬──────────────────────────────┐
│ Agent           │ Batchable?        │ Why                          │
├─────────────────┼───────────────────┼──────────────────────────────┤
│ 1 Candidate Ext │ N/A (MAP phase)   │ Runs per chunk, not batched  │
│ 2 Source Class  │ N/A (MAP phase)   │ Runs per chunk               │
│ 3 Name Normal   │ N/A (MAP phase)   │ Runs per chunk               │
│ Chunk Merger    │ NO                │ Needs ALL chunks globally    │
│ 4 Dedup+Source  │ YES               │ Per-person (with caveats)    │
│ 5 CSM Classifr  │ YES               │ Per-person                   │
│ 6 Country Ovrd  │ YES               │ Per-person                   │
│ 7 Title Extract │ YES               │ Per-person                   │
│ 8 Scoring       │ YES               │ Per-person                   │
│ 9 Reason Assm   │ NO                │ Needs all tags assembled     │
│ 10 Formatter    │ NO                │ Needs global J4 ordering     │
│ 11 Critic       │ NO                │ Reviews full output          │
│ 12 Refiner      │ NO                │ Fixes full output            │
└─────────────────┴───────────────────┴──────────────────────────────┘

Batching window: Agents 4 → 5 → 6+7+8
After that: merge batch results, renumber ids, then Agents 9-12 on full set


══════════════════════════════════════════════════════════════════════════
SUMMARY: Wave Execution Timeline
══════════════════════════════════════════════════════════════════════════

Time →

┌── MAP (per chunk, parallelizable across chunks) ──────────────────┐
│                                                                    │
│  Wave 1: Agent 1 ═══╗                                             │
│          Agent 2 ═══╩══→ Wave 2: Agent 3 ──→ chunk results        │
│                                                                    │
│  (× N chunks, parallel or sequential)                              │
└────────────────────────────────────────────────────────────────────┘
│
▼
┌── MERGE ──────────────────────────────────────────────────────────┐
│  Chunk Merger Agent (single call, all chunks)                      │
└────────────────────────────────────────────────────────────────────┘
│
▼
┌── REDUCE (on merged candidates, optional batching) ───────────────┐
│                                                                    │
│  Wave 3: Agent 4 (dedup)                                           │
│       ▼                                                            │
│  Wave 4: Agent 5 (classify)                                        │
│       ▼                                                            │
│  Wave 5: Agent 6 ═══╗                                              │
│          Agent 7 ═══╬══→ merge parallel results                    │
│          Agent 8 ═══╝                                              │
│       ▼                                                            │
│  (batch merge + id renumber if batched)                            │
│       ▼                                                            │
│  Wave 6: Agent 9 (reason assembly — full set)                      │
│       ▼                                                            │
│  Wave 7: Agent 10 (format + validate — full set)                   │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
│
▼
┌── CRITIC LOOP ────────────────────────────────────────────────────┐
│  Wave 8: Agent 11 (first critic)                                   │
│       ▼                                                            │
│  Loop: Agent 12 → Agent 11 → exit when score ≥ 0.85               │
└────────────────────────────────────────────────────────────────────┘
│
▼
finalOutput (JSON string)