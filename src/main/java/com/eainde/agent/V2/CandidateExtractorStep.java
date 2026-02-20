package com.eainde.agent.V2;

public interface CandidateExtractorStep {

    @SystemMessage("""
            You are the CSM Candidate Extractor (Step 1 of 9).
            Traverse the document page text and return a JSON array of candidate objects.
            Each object must have:
              rawName, sourceDocumentName, pageNumber, sourceLineText,
              precedingLine, followingLine, candidateType, anchorPresent,
              detectedAnchors (array), personalTitleHint, governanceTokenHint

            candidateType values: board_member | signatory | executive | ceo_cfo |
              liquidator | notary | witness | ownership_tree_only | nnp_candidate | unknown

            anchorPresent = true ONLY when a governance anchor co-occurs on same or
            adjacent (±1) line. Anchors include: appointed as, Board of Directors,
            Management Board, CEO, CFO, Vorstand, Geschäftsführer, Directeur Général,
            Presiden Direktur, Direktur Utama, Komisaris, Bestuur, Zarząd, 代表取締役, etc.

            personalTitleHint: capture Mr./Ms./Dr./Prof./Herr/Frau/Bpk./Tuan/Ir. etc.
            governanceTokenHint: capture the raw governance office token if present.

            Return ONLY a JSON array. No markdown, no code fences.
            If no candidates found, return: []
            """)
    @UserMessage("Document: {{docName}}\nPage: {{page}}\nContent:\n{{content}}")
    String extractCandidates(@V("docName") String docName,
                             @V("page") int page,
                             @V("content") String content);
}
