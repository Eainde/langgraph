package com.eainde.agent.V2.chunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits large documents into overlapping page-range chunks suitable for
 * LLM processing within context window limits.
 *
 * <h3>Why overlap?</h3>
 * <p>CSM extraction requires cross-page context: a person's name may appear
 * on one page with their governance title on the next. Without overlap, chunk
 * boundaries would sever these linkages, causing missed JT anchors (JT.6)
 * and incorrect extractions.</p>
 *
 * <h3>Usage:</h3>
 * <pre>
 * DocumentChunker chunker = DocumentChunker.builder()
 *         .pagesPerChunk(20)
 *         .overlapPages(5)
 *         .pageDelimiter(Pattern.compile("\\f|--- Page \\d+ ---"))
 *         .build();
 *
 * if (chunker.needsChunking(sourceText, 100_000)) {
 *     List&lt;ChunkContext&gt; chunks = chunker.chunk(sourceText);
 *     // process each chunk through agents 1-2
 * }
 * </pre>
 *
 * <p>This class is pure logic with no Spring dependencies — safe for unit testing.</p>
 */
public class DocumentChunker {

    private static final Logger log = LoggerFactory.getLogger(DocumentChunker.class);

    /** Default: form-feed character (standard PDF-to-text page delimiter) */
    private static final Pattern DEFAULT_PAGE_DELIMITER = Pattern.compile("\\f");

    /** Heuristic: average tokens per word for English/mixed text */
    private static final double TOKENS_PER_WORD = 0.75;

    private final int pagesPerChunk;
    private final int overlapPages;
    private final Pattern pageDelimiter;

    private DocumentChunker(Builder builder) {
        this.pagesPerChunk = builder.pagesPerChunk;
        this.overlapPages = builder.overlapPages;
        this.pageDelimiter = builder.pageDelimiter;

        if (overlapPages >= pagesPerChunk) {
            throw new IllegalArgumentException(
                    "overlapPages (" + overlapPages + ") must be < pagesPerChunk (" + pagesPerChunk + ")");
        }
    }

    // =========================================================================
    //  Public API
    // =========================================================================

    /**
     * Determines whether a document needs chunking based on estimated token count.
     *
     * @param sourceText           the full document text
     * @param maxTokenEstimate     the token budget (e.g., 80% of model context window)
     * @return true if the document likely exceeds the token budget
     */
    public boolean needsChunking(String sourceText, int maxTokenEstimate) {
        if (sourceText == null || sourceText.isBlank()) return false;
        int estimatedTokens = estimateTokens(sourceText);
        boolean needs = estimatedTokens > maxTokenEstimate;
        if (needs) {
            log.info("Document needs chunking: ~{} estimated tokens exceeds {} limit",
                    estimatedTokens, maxTokenEstimate);
        }
        return needs;
    }

    /**
     * Splits a document into overlapping page-range chunks.
     *
     * <p>If the document has no page delimiters (or is a single page),
     * returns a single chunk containing the entire text.</p>
     *
     * @param sourceText the full document text
     * @return list of chunks, never empty
     */
    public List<ChunkContext> chunk(String sourceText) {
        if (sourceText == null || sourceText.isBlank()) {
            return Collections.singletonList(new ChunkContext(
                    0, 1, 1, -1, -1, "", 1));
        }

        List<String> pages = splitIntoPages(sourceText);
        log.info("Document split into {} pages (pagesPerChunk={}, overlap={})",
                pages.size(), pagesPerChunk, overlapPages);

        if (pages.size() <= pagesPerChunk) {
            log.info("Document fits in single chunk — no splitting needed");
            return Collections.singletonList(new ChunkContext(
                    0, 1, pages.size(), -1, -1, sourceText, 1));
        }

        int stride = pagesPerChunk - overlapPages;
        List<ChunkContext> chunks = new ArrayList<>();

        int chunkIndex = 0;
        for (int start = 0; start < pages.size(); start += stride) {
            int end = Math.min(start + pagesPerChunk, pages.size());

            // Build chunk text by joining the pages
            StringBuilder chunkText = new StringBuilder();
            for (int p = start; p < end; p++) {
                if (chunkText.length() > 0) chunkText.append('\f');
                chunkText.append(pages.get(p));
            }

            // Determine overlap zone (pages that also appear in the previous chunk)
            int overlapStart = -1;
            int overlapEnd = -1;
            if (chunkIndex > 0) {
                overlapStart = start + 1;  // 1-based page number
                overlapEnd = Math.min(start + overlapPages, end) ;  // 1-based
            }

            int totalChunks = (int) Math.ceil((double) (pages.size() - overlapPages) / stride);
            // Correct for edge case where last chunk is exactly at boundary
            if ((pages.size() - overlapPages) % stride == 0 && pages.size() > pagesPerChunk) {
                totalChunks = Math.max(totalChunks, chunkIndex + 1);
            }

            chunks.add(new ChunkContext(
                    chunkIndex,
                    start + 1,              // 1-based page start
                    end,                     // 1-based page end (end is exclusive in loop but pages list is 0-based)
                    overlapStart,
                    overlapEnd,
                    chunkText.toString(),
                    -1                       // placeholder — updated below
            ));

            chunkIndex++;

            // Stop if we've reached the end
            if (end >= pages.size()) break;
        }

        // Fix totalChunks on all records
        int total = chunks.size();
        List<ChunkContext> result = new ArrayList<>(total);
        for (ChunkContext c : chunks) {
            result.add(new ChunkContext(
                    c.chunkIndex(), c.pageStart(), c.pageEnd(),
                    c.overlapStartPage(), c.overlapEndPage(),
                    c.chunkText(), total));
        }

        log.info("Document split into {} chunks", total);
        for (ChunkContext c : result) {
            log.debug("  {}", c);
        }

        return Collections.unmodifiableList(result);
    }

    /**
     * Estimates the token count for a text string using a word-count heuristic.
     *
     * <p>Heuristic: split on whitespace, multiply by {@value TOKENS_PER_WORD}.
     * This is intentionally conservative — it's better to chunk unnecessarily
     * than to exceed the context window.</p>
     *
     * @param text the text to estimate
     * @return estimated token count
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        // Split on whitespace and count — simple but effective for mixed-language text
        int wordCount = text.split("\\s+").length;
        return (int) Math.ceil(wordCount / TOKENS_PER_WORD);
    }

    // =========================================================================
    //  Internal
    // =========================================================================

    /**
     * Splits text into pages using the configured delimiter.
     * Trims each page and filters out empty pages.
     */
    private List<String> splitIntoPages(String text) {
        String[] raw = pageDelimiter.split(text);
        List<String> pages = new ArrayList<>();
        for (String page : raw) {
            String trimmed = page.strip();
            if (!trimmed.isEmpty()) {
                pages.add(trimmed);
            }
        }
        // If no delimiters found, treat entire text as single page
        if (pages.isEmpty()) {
            pages.add(text.strip());
        }
        return pages;
    }

    // =========================================================================
    //  Builder
    // =========================================================================

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a chunker with default settings (20 pages/chunk, 5-page overlap, form-feed delimiter).
     */
    public static DocumentChunker withDefaults() {
        return builder().build();
    }

    public static class Builder {
        private int pagesPerChunk = 20;
        private int overlapPages = 5;
        private Pattern pageDelimiter = DEFAULT_PAGE_DELIMITER;

        /**
         * Number of pages per chunk. Default: 20.
         * Tune based on your model's context window and average page density.
         */
        public Builder pagesPerChunk(int pagesPerChunk) {
            if (pagesPerChunk < 2) throw new IllegalArgumentException("pagesPerChunk must be >= 2");
            this.pagesPerChunk = pagesPerChunk;
            return this;
        }

        /**
         * Number of overlapping pages between adjacent chunks. Default: 5.
         * Must be less than pagesPerChunk.
         */
        public Builder overlapPages(int overlapPages) {
            if (overlapPages < 0) throw new IllegalArgumentException("overlapPages must be >= 0");
            this.overlapPages = overlapPages;
            return this;
        }

        /**
         * Regex pattern for page delimiters. Default: form-feed (\\f).
         * Examples: "\\f", "--- Page \\d+ ---", "\\n\\n---\\n\\n"
         */
        public Builder pageDelimiter(Pattern pageDelimiter) {
            this.pageDelimiter = pageDelimiter;
            return this;
        }

        /**
         * Convenience: set page delimiter from a string regex.
         */
        public Builder pageDelimiter(String regex) {
            this.pageDelimiter = Pattern.compile(regex);
            return this;
        }

        public DocumentChunker build() {
            return new DocumentChunker(this);
        }
    }
}