package com.eainde.agent.V2;


/**
 * ┌─────────────────────────────────────────────────────────┐
 * │                   PRE-LLM (Java code)                   │
 * │                                                          │
 * │  Big Document (200 pages)                                │
 * │    │                                                     │
 * │    ▼                                                     │
 * │  Chunker: Split into overlapping page ranges             │
 * │    Chunk A: pages 1-20                                   │
 * │    Chunk B: pages 16-35   ← 5-page overlap               │
 * │    Chunk C: pages 31-50                                  │
 * │    ...                                                   │
 * └─────────────────────────────────────────────────────────┘
 *
 *                  Chunk A              Chunk B              Chunk C
 *                       │                    │                    │
 *                       ▼                    ▼                    ▼
 *               ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
 *               │ 1. Source     │   │ 1. Source     │   │ 1. Source     │
 *               │    Classifier │   │    Classifier │   │    Classifier │
 *               └──────┬───────┘   └──────┬───────┘   └──────┬───────┘
 *                      ▼                   ▼                   ▼
 *               ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
 *               │ 2. Person    │   │ 2. Person    │   │ 2. Person    │
 *               │    Extractor │   │    Extractor │   │    Extractor │
 *               └──────┬───────┘   └──────┬───────┘   └──────┬───────┘
 *                      │                   │                   │
 *                      └───────────┬───────┘───────────────────┘
 *                                  ▼
 *                     ┌────────────────────────┐
 *                     │  CHUNK MERGER          │  ← NEW AGENT
 *                     │  (dedup + source merge)│
 *                     └────────────┬───────────┘
 *                                  │ mergedCandidates (global, deduped)
 *                                  ▼
 *                     ┌────────────────────────┐
 *                     │ 3. CSM Classifier      │
 *                     │ 4. Scorer              │
 *                     │ 5. Output Assembler    │
 *                     │ 6-7. Critic/Refiner    │
 *                     └────────────────────────┘
 */
public class Test {
}
