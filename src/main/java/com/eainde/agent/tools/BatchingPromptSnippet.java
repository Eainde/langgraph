package com.eainde.agent.tools;


/**
 * Standard prompt snippet that instructs the LLM on batch output protocol.
 *
 * <p>This text is appended to every agent's system prompt. It tells the LLM
 * to use the {@code submit_batch} tool when output exceeds ~40 records.</p>
 *
 * <h3>Usage:</h3>
 * <pre>
 * String fullSystemPrompt = agentSystemPrompt + BatchingPromptSnippet.INSTRUCTION;
 * </pre>
 *
 * <p>Or store in database as a shared prompt fragment and append during template resolution.</p>
 */
public final class BatchingPromptSnippet {

    private BatchingPromptSnippet() {} // constants only

    /**
     * Append this to every agent's system prompt.
     *
     * <p>If the agent has a small output (e.g., Source Classifier with 5 sources),
     * the LLM ignores this instruction and returns JSON directly.
     * The tool is only used when output volume demands it.</p>
     */
    public static final String INSTRUCTION = """
            
            ## OUTPUT BATCHING PROTOCOL
            
            You have access to a tool called `submit_batch`.
            
            WHEN TO USE:
            - If your output contains MORE than 40 records/candidates, you MUST
              use the submit_batch tool to submit results in batches of ~40 records.
            - If your output contains 40 or FEWER records, return them directly
              as a JSON object WITHOUT using the tool.
            
            HOW TO USE:
            1. Process the first ~40 records.
            2. Call submit_batch with a JSON object containing those records.
               Use the SAME JSON schema as your normal output.
               Example: {"raw_names": [{"id": 1, ...}, {"id": 2, ...}, ...]}
            3. Read the tool's response (it confirms receipt and running total).
            4. Continue processing the next ~40 records.
            5. Call submit_batch again with the next batch.
            6. Repeat until ALL records have been submitted.
            7. After the last batch, return ONLY a text summary:
               "Extraction complete. {count} records submitted in {batches} batches."
               Do NOT return JSON after the last batch — just the summary text.
            
            IMPORTANT RULES:
            - Maintain sequential ID numbering across batches.
              If batch 1 ends at id=40, batch 2 starts at id=41.
            - Each batch must use the exact same JSON schema.
            - Do NOT skip any records between batches.
            - Do NOT stop early — submit ALL records.
            """;

    /**
     * Short version for agents that are unlikely to need batching
     * but should have the capability just in case.
     */
    public static final String INSTRUCTION_SHORT = """
            
            ## OUTPUT BATCHING
            If your output exceeds 40 records, use the submit_batch tool to submit
            in batches of ~40 records each. Otherwise, return JSON directly.
            """;
}
