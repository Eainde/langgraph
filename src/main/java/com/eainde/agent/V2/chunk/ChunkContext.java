package com.eainde.agent.V2.chunk;


/**
 * Represents a single chunk of a large document split for LLM processing.
 *
 * <p>Each chunk covers a contiguous range of pages. Chunks may overlap
 * at their boundaries to preserve cross-page context (e.g., a person's
 * name on page 20 with their governance title on page 21).</p>
 *
 * <pre>
 *   Chunk 0: pages [1..20]   overlapStartPage = -1 (no overlap on first chunk)
 *   Chunk 1: pages [16..35]  overlapStartPage = 16 (pages 16-20 are overlap)
 *   Chunk 2: pages [31..50]  overlapStartPage = 31 (pages 31-35 are overlap)
 * </pre>
 *
 * @param chunkIndex       zero-based index of this chunk
 * @param pageStart        1-based inclusive start page
 * @param pageEnd          1-based inclusive end page
 * @param overlapStartPage 1-based page where the overlap zone begins
 *                         (-1 for the first chunk which has no leading overlap)
 * @param overlapEndPage   1-based page where the overlap zone ends
 *                         (-1 for the first chunk)
 * @param chunkText        the raw text content of this chunk
 * @param totalChunks      total number of chunks the document was split into
 */
public record ChunkContext(
        int chunkIndex,
        int pageStart,
        int pageEnd,
        int overlapStartPage,
        int overlapEndPage,
        String chunkText,
        int totalChunks
) {

    /**
     * @return true if this is the first chunk (no leading overlap)
     */
    public boolean isFirstChunk() {
        return chunkIndex == 0;
    }

    /**
     * @return true if this is the last chunk
     */
    public boolean isLastChunk() {
        return chunkIndex == totalChunks - 1;
    }

    /**
     * @return true if a given page falls within the overlap zone of this chunk
     */
    public boolean isOverlapPage(int page) {
        if (overlapStartPage < 0) return false;
        return page >= overlapStartPage && page <= overlapEndPage;
    }

    /**
     * @return the number of non-overlap (unique) pages in this chunk
     */
    public int uniquePageCount() {
        if (overlapStartPage < 0) return pageEnd - pageStart + 1;
        return pageEnd - overlapEndPage;
    }

    @Override
    public String toString() {
        return String.format("Chunk[%d/%d, pages %d-%d, overlap %d-%d]",
                chunkIndex + 1, totalChunks, pageStart, pageEnd,
                overlapStartPage, overlapEndPage);
    }
}
