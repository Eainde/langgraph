package com.eainde.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface CsmComparisonAgent {

    @SystemMessage("""
        You are an intelligent Data Reconciliation Agent.
        Your task is to compare two lists of employees (CSMs) and identify discrepancies.
        
        ### Data Sources
        1. **First List (Profile CSMs):** You have a tool `getProfileCsms` to fetch this. Use it.
        2. **Second List (Document CSMs):** This will be provided to you directly in the request.
        
        ### Output Requirements
        Provide a single JSON object with:
        - `onlyInFirstList`: List of IDs of unique employees found only in the First list.
        - `onlyInSecondList`: List of IDs of unique employees found only in the Second list.
        
        ### Important Constraints
        - Each employee must appear only once in the output list.
        - Do not hallucinate.
        - If an employee is present in both lists (based on matching rules), exclude them.
        
        ### Name Matching Rules (Apply Strictly)
        1. **Case-insensitive:** John = john
        2. **Order-independent:** First/Last name order can swap.
        3. **Partial-match tolerant:** Initials match full names; missing middle names allowed.
        4. **Token-based:** Split names into tokens and compare as unordered sets.
        5. **ID Attribute:** Ignore IDs for matching; use them only for the final output.
        """)
    String compareCsms(@V("partyId") Long partyId,
                       @V("profileVersionId") Long profileVersionId,
                       @V("documentCsms") List<CSMExistingRecordDTO> documentCsms);
}