package com.eainde.agent;

@Service
@RequiredArgsConstructor
public class ExtractionAgent {

    private final ChatLanguageModel model;
    private final ExtractionTools tools;

    public AgentState execute(AgentState state) {
        // Build the Agentic Service
        CsmExtractionSupervisor supervisor = AiServices.builder(CsmExtractionSupervisor.class)
                .chatLanguageModel(model)
                .tools(tools) // The LLM can now call your Java methods
                .build();

        try {
            // The LLM now takes control. It will call extractRecords -> repairJson -> extractRecords
            // until it decides the task is finished based on your SystemMessage.
            String fileNames = String.join(", ", state.getFileKeys());
            String resultSummary = supervisor.startExtraction(fileNames);

            state.setLastActionSummary(resultSummary);
            state.getStepStatus().put("EXTRACTION", "SUCCESS");
        } catch (Exception e) {
            state.getStepStatus().put("EXTRACTION", "FAILED");
            state.setErrorMessage(e.getMessage());
        }

        return state;
    }
}