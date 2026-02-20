# CSM Extraction Pipeline V6 — 12-Agent Architecture

## Overview

Wave-based pipeline with Java orchestrator, 12 focused agents, parallel execution
in Waves 1 and 5, and a critic-refiner quality loop.

**Design principles:**
- Agent ↔ Agent: JSON strings via AgenticScope
- Orchestration logic (merge, loop exit): DTOs via Jackson at boundaries
- Java orchestrator: deterministic wave execution, no LLM routing
- Each agent has a narrow, focused prompt (≤ 1500 words)

---

## Pipeline Flow

```
sourceText + fileNames
  │
  │  ┌──────────────────────────────────────────────────────────────┐
  │  │  WAVE 1 — PARALLEL (both read sourceText independently)     │
  │  │                                                              │
  │  │  ┌─────────────────────┐   ┌──────────────────────────┐    │
  ├──┼─►│ Agent 1             │   │ Agent 2                  │    │
  │  │  │ Candidate Extractor │   │ Source Classifier        │    │
  │  │  │ (RC1-3)             │   │ (B, IDandV)              │    │
  │  │  │                     │   │                          │    │
  │  │  │ → rawNames          │   │ → sourceClassification   │    │
  │  │  └─────────┬───────────┘   └─────────────┬────────────┘    │
  │  └────────────┼─────────────────────────────┼─────────────────┘
  │               │                             │
  │               ▼                             ▼
  │  ┌──────────────────────────────────────────────────────────────┐
  │  │  WAVE 2                                                      │
  │  │  ┌───────────────────────────────────────────────────────┐  │
  │  │  │ Agent 3: Name Normalizer (L1-L9)                      │  │
  │  │  │ reads: rawNames + sourceClassification                │  │
  │  │  │ → normalizedCandidates                                │  │
  │  │  └──────────────────────────┬────────────────────────────┘  │
  │  └─────────────────────────────┼───────────────────────────────┘
  │                                │
  │                                ▼
  │  ┌──────────────────────────────────────────────────────────────┐
  │  │  WAVE 3                                                      │
  │  │  ┌───────────────────────────────────────────────────────┐  │
  │  │  │ Agent 4: Dedup + Source Linkage (A6, RC4)             │  │
  │  │  │ reads: normalizedCandidates + sourceClassification    │  │
  │  │  │ → dedupedCandidates                                   │  │
  │  │  └──────────────────────────┬────────────────────────────┘  │
  │  └─────────────────────────────┼───────────────────────────────┘
  │                                │
  │                                ▼
  │  ┌──────────────────────────────────────────────────────────────┐
  │  │  WAVE 4                                                      │
  │  │  ┌───────────────────────────────────────────────────────┐  │
  │  │  │ Agent 5: CSM Classifier (A1-5, C1-13, NO CP)         │  │
  │  │  │ reads: dedupedCandidates + sourceText + srcClass      │  │
  │  │  │ → classifiedCandidates                                │  │
  │  │  └──────────────────────────┬────────────────────────────┘  │
  │  └─────────────────────────────┼───────────────────────────────┘
  │                                │
  │                                ▼
  │  ┌──────────────────────────────────────────────────────────────┐
  │  │  WAVE 5 — PARALLEL (all read classifiedCandidates)          │
  │  │                                                              │
  │  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
  │  │  │ Agent 6      │  │ Agent 7      │  │ Agent 8          │  │
  │  │  │ Country      │  │ Title        │  │ Scoring Engine   │  │
  │  │  │ Override     │  │ Extractor    │  │ (D0-6, QG1-10)  │  │
  │  │  │ (CP×14)      │  │ (JT,PT,GATE)│  │                  │  │
  │  │  │              │  │              │  │                  │  │
  │  │  │→ country     │  │→ title       │  │→ scored          │  │
  │  │  │  Overrides   │  │  Extractions │  │  Candidates      │  │
  │  │  └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘  │
  │  └─────────┼─────────────────┼───────────────────┼─────────────┘
  │            │                 │                   │
  │            └────────┬────────┴───────────┬───────┘
  │                     │                    │
  │                     ▼                    │
  │         ┌───────────────────────┐        │
  │         │ JAVA MERGE            │◄───────┘
  │         │ Match by candidate id │
  │         │ Overlay fields:       │
  │         │  A6: isCsm, CP note   │
  │         │  A7: jobTitle, PT     │
  │         │  A8: score, breakdown │
  │         │ → enrichedCandidates  │
  │         └───────────┬───────────┘
  │                     │
  │                     ▼
  │  ┌──────────────────────────────────────────────────────────────┐
  │  │  WAVE 6                                                      │
  │  │  ┌───────────────────────────────────────────────────────┐  │
  │  │  │ Agent 9: Reason Assembler (R1-R8)                     │  │
  │  │  │ reads: enrichedCandidates                             │  │
  │  │  │ → reasonedCandidates                                  │  │
  │  │  └──────────────────────────┬────────────────────────────┘  │
  │  └─────────────────────────────┼───────────────────────────────┘
  │                                │
  │                                ▼
  │  ┌──────────────────────────────────────────────────────────────┐
  │  │  WAVE 7                                                      │
  │  │  ┌───────────────────────────────────────────────────────┐  │
  │  │  │ Agent 10: Output Formatter (J1-7, RC8-9, Z1-7)       │  │
  │  │  │ reads: reasonedCandidates + fileNames                 │  │
  │  │  │ → finalOutput                                         │  │
  │  │  └──────────────────────────┬────────────────────────────┘  │
  │  └─────────────────────────────┼───────────────────────────────┘
  │                                │
  │                                ▼
  │  ┌──────────────────────────────────────────────────────────────┐
  │  │  WAVE 8 + CRITIC-REFINER LOOP                                │
  │  │                                                              │
  │  │  ┌───────────────────────────────────────────────────────┐  │
  │  │  │ Agent 11: First Critic (compliance checklist)         │  │
  │  │  │ reads: finalOutput + sourceText                       │  │
  │  │  │ → extractionReview (score + issues)                   │  │
  │  │  └──────────────────────────┬────────────────────────────┘  │
  │  │                             │                                │
  │  │                       score >= 0.85?                         │
  │  │                        ┌────┴────┐                           │
  │  │                     YES│         │NO                         │
  │  │                        ▼         ▼                           │
  │  │                      DONE    ┌────────────────────────┐     │
  │  │                        │     │ LOOP (max 3):          │     │
  │  │                        │     │                        │     │
  │  │                        │     │ Agent 12: Refiner      │     │
  │  │                        │     │  → finalOutput         │     │
  │  │                        │     │       │                │     │
  │  │                        │     │ Agent 11: Loop Critic  │     │
  │  │                        │     │  → extractionReview    │     │
  │  │                        │     │       │                │     │
  │  │                        │     │  score >= 0.85? ─YES─► │DONE │
  │  │                        │     │       │NO              │     │
  │  │                        │     │       ▼ next iter      │     │
  │  │                        │     └────────────────────────┘     │
  │  └────────────────────────┼────────────────────────────────────┘
                              │
                              ▼
                      finalOutput (JSON)
```

---

## Agent-to-Section Mapping

| # | Agent | Spec Sections | Prompt Size | Input Keys | Output Key |
|---|-------|---------------|-------------|------------|------------|
| 1 | csm-candidate-extractor | RC1-3 | ~400 words | sourceText, fileNames | rawNames |
| 2 | csm-source-classifier | B, IDandV, B0-4 | ~500 words | sourceText, fileNames | sourceClassification |
| 3 | csm-name-normalizer | L1-L9, A6 partial | ~600 words | rawNames, sourceClassification | normalizedCandidates |
| 4 | csm-dedup-linker | A6 full, RC4 | ~500 words | normalizedCandidates, sourceClassification | dedupedCandidates |
| 5 | csm-classifier | A1-5, C1-C13 | ~800 words | dedupedCandidates, sourceText, sourceClassification | classifiedCandidates |
| 6 | csm-country-override | CP ×14 | ~900 words | classifiedCandidates, sourceClassification | countryOverrides |
| 7 | csm-title-extractor | JT, PT, ANCHOR | ~500 words | classifiedCandidates, sourceText | titleExtractions |
| 8 | csm-scoring-engine | D0-6, QG1-10 | ~700 words | classifiedCandidates | scoredCandidates |
| 9 | csm-reason-assembler | R1-8, B2-3 | ~600 words | enrichedCandidates | reasonedCandidates |
| 10 | csm-output-formatter | J1-7, RC8-9, Z1-7 | ~500 words | reasonedCandidates, fileNames | finalOutput |
| 11 | csm-extraction-critic | All (condensed) | ~700 words | finalOutput, sourceText | extractionReview |
| 12 | csm-output-refiner | R, J, B2 + feedback | ~300 words | finalOutput, extractionReview, enrichedCandidates | finalOutput |

**Total prompt load: ~7,000 words across 12 prompts** (vs ~15,000 words in single monolith)

---

## Wave 5 Merge Logic

The Java orchestrator merges three parallel agent outputs by matching on candidate `id`:

```
classifiedCandidates (base from Agent 5)
  ├── Agent 6 overlays: isCsm, countryProfileApplied, countryOverrideNote
  ├── Agent 7 overlays: jobTitle, personalTitle, anchorNote
  └── Agent 8 overlays: score, scoreBreakdown, qualityGateNotes
  = enrichedCandidates
```

**Merge rules:**
1. Base record = classifiedCandidates[i] (from Agent 5)
2. For each base record, find matching id in Agent 6/7/8 outputs
3. Agent 6's `isCsm` OVERRIDES Agent 5's if country profile applies (stricter rules)
4. Agent 7's titles are ADDED (jobTitle, personalTitle were null from Agent 5)
5. Agent 8's scores are ADDED (score, breakdown, QG notes)
6. If an agent didn't produce output for a candidate, that candidate keeps base values
7. Fallback: if merge fails entirely, use scoredCandidates as enrichedCandidates

---

## Scope Variables (all JSON strings)

| Key | Written By | Read By | Type |
|-----|-----------|---------|------|
| sourceText | Orchestrator | 1, 2, 5, 7, 11 | String (raw document) |
| fileNames | Orchestrator | 1, 2, 10 | String |
| rawNames | Agent 1 | 3 | JSON |
| sourceClassification | Agent 2 | 3, 4, 5, 6 | JSON |
| normalizedCandidates | Agent 3 | 4 | JSON |
| dedupedCandidates | Agent 4 | 5 | JSON |
| classifiedCandidates | Agent 5 | 6, 7, 8 | JSON |
| countryOverrides | Agent 6 | Merge | JSON |
| titleExtractions | Agent 7 | Merge | JSON |
| scoredCandidates | Agent 8 | Merge | JSON |
| enrichedCandidates | Merge | 9, 12 | JSON |
| reasonedCandidates | Agent 9 | 10 | JSON |
| finalOutput | Agent 10/12 | 11, 12 | JSON |
| extractionReview | Agent 11 | 12 | JSON |

---

## Comparison: V5 (7-agent) vs V6 (12-agent)

| Aspect | V5 (7 agents) | V6 (12 agents) |
|--------|---------------|----------------|
| Prompt size | ~2,000 words per agent | ~600 words per agent |
| Parallelism | None (sequential) | Wave 1 (2 agents), Wave 5 (3 agents) |
| LLM calls | 7 + loop (max 13) | 12 + loop (max 18) |
| Latency (sequential) | 7 × avg call time | 8 waves × avg call time |
| Latency (parallel) | 7 × avg call time | 8 waves (but 2 waves have parallelism) |
| Dedup ordering | Inside extraction agent | After normalization (correct) |
| Country profiles | Bundled in classifier | Separate agent (modular) |
| Title extraction | Inside extraction agent | Separate with ANCHOR GATE |
| Critic loop | ✓ | ✓ |
| Prompt accuracy | Good (broader context) | Better (narrower focus) |
| Maintainability | Fewer files, harder to modify | More files, easier to modify one agent |

---

## Integration Point

In `CsmExtractionNode` (or equivalent LangGraph node):

```java
// Before (V5):
v5Config.buildExtractionSubWorkflow().invoke(scope);
String result = scope.readState("finalOutput", "{}");

// After (V6):
String result = v6Config.execute(sourceText, fileNames, scope);
// Or for typed access:
ExtractionOutput output = objectMapper.readValue(result, ExtractionOutput.class);
```

---

## Future: Map-Reduce Extension

For large documents, the chunked pipeline wraps this flow:

```
MAP (per chunk):   Agents 1-3 (extract, classify sources, normalize)
MERGE:             Chunk Merger Agent (cross-chunk dedup)
REDUCE:            Agents 4-12 (dedup, classify, score, assemble, format, critic)
```

Split point: Agent 3→4 boundary. Agents 1-3 need raw text. Agent 4+ works on structured JSON.