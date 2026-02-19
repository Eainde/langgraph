package com.db.clm.kyc.ai.nexus.agent;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Builder
public class ContentAwareUntypedAgent implements UntypedAgent {

    private final ChatLanguageModel chatModel;
    private final String systemInstruction;
    private final String userPromptText;
    private final String agentName;
    private final String outputKey;

    // Pre-bound at construction time — before passing to sequenceBuilder
    private List<Content> boundContents;

    // ----------------------------------------------------------------
    // Fluent binder — call BEFORE handing to sequenceBuilder/parallel/loop
    // ----------------------------------------------------------------
    public ContentAwareUntypedAgent withBoundContent(List<Content> contents) {
        this.boundContents = new ArrayList<>(contents);
        return this;
    }

    // ----------------------------------------------------------------
    // Core internal execution
    // ----------------------------------------------------------------
    private String executeInternal(Map<String, Object> input) {
        List<Content> fullContents = new ArrayList<>();

        // 1. DB prompt text as instruction prefix
        fullContents.add(TextContent.from(userPromptText));

        // 2. Pre-bound files (PDFs, images, etc.)
        if (boundContents != null && !boundContents.isEmpty()) {
            fullContents.addAll(boundContents);
        }

        // 3. Any string input passed from previous agent in the chain
        //    The map may carry prior agent output keyed by outputKey or "input"
        if (input != null && !input.isEmpty()) {
            input.values().stream()
                    .filter(v -> v instanceof String)
                    .map(v -> TextContent.from((String) v))
                    .forEach(fullContents::add);
        }

        UserMessage userMessage = UserMessage.from(fullContents);
        SystemMessage systemMessage = SystemMessage.from(systemInstruction);

        log.debug("[{}] invoking with {} total content items, inputKeys={}",
                agentName,
                fullContents.size(),
                input != null ? input.keySet() : "none");

        ChatResponse response = chatModel.chat(systemMessage, userMessage);
        String result = response.aiMessage().text();

        log.debug("[{}] response length: {}", agentName, result.length());
        return result;
    }

    // ----------------------------------------------------------------
    // UntypedAgent contract — method 1
    // Called by sequenceBuilder / parallelBuilder / loopBuilder
    // ----------------------------------------------------------------
    @Override
    public Object invoke(Map<String, Object> input) {
        return executeInternal(input);
    }

    // ----------------------------------------------------------------
    // UntypedAgent contract — method 2
    // Called when agentic scope (memory/session) context is needed
    // ----------------------------------------------------------------
    @Override
    public ResultWithAgenticScope<String> invokeWithAgenticScope(Map<String, Object> input) {
        String result = executeInternal(input);

        // Wrap result — AgenticScope is null since we manage no memory here
        // If you add ChatMemory support later, wire it here
        return ResultWithAgenticScope.of(result, null);
    }

    // ----------------------------------------------------------------
    // UntypedAgent contract — method 3
    // Scope is stateless for content-aware agents (no memory per session)
    // Return null unless you wire ChatMemory later
    // ----------------------------------------------------------------
    @Override
    public AgenticScope getAgenticScope(Object memoryId) {
        return null;
    }

    // ----------------------------------------------------------------
    // UntypedAgent contract — method 4
    // Nothing to evict — stateless
    // ----------------------------------------------------------------
    @Override
    public boolean evictAgenticScope(Object memoryId) {
        return false;
    }
}


ContentAwareUntypedAgent extractionAgent = (ContentAwareUntypedAgent)
        agentFactory.createContentAware(
                AgentSpec.builder()
                        .agentName("csm-extraction-agent")
                        .outputKey("extractedCsm")
                        .build()
        ).withBoundContent(documents);