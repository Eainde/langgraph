package ai.nexus.agent;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Builder
public class ContentAwareUntypedAgent implements UntypedAgent {  // <-- KEY

    private final ChatLanguageModel chatModel;
    private final String systemInstruction;
    private final String userPromptText;
    private final String agentName;
    private final String outputKey;

    /**
     * Pre-bound content (PDFs, images, etc.) — set before chaining.
     * The sequence/loop/parallel builder just calls execute(String)
     * and this content is automatically included.
     */
    private List<Content> boundContents;

    // ----------------------------------------------------------------
    // Called by AgenticServices.sequenceBuilder / parallel / loop
    // ----------------------------------------------------------------
    @Override
    public String execute(String input) {
        List<Content> fullContents = new ArrayList<>();

        // 1. DB prompt as instruction prefix
        fullContents.add(TextContent.from(userPromptText));

        // 2. Pre-bound files (PDFs, images etc.)
        if (boundContents != null && !boundContents.isEmpty()) {
            fullContents.addAll(boundContents);
        }

        // 3. Runtime input from previous agent in chain (if any)
        if (input != null && !input.isBlank()) {
            fullContents.add(TextContent.from(input));
        }

        UserMessage userMessage = UserMessage.from(fullContents);
        SystemMessage systemMessage = SystemMessage.from(systemInstruction);

        log.debug("[{}] executing with {} content items + runtime input",
                agentName, fullContents.size());

        ChatResponse response = chatModel.chat(systemMessage, userMessage);
        return response.aiMessage().text();
    }

    // ----------------------------------------------------------------
    // Fluent binder — call this before passing to sequenceBuilder
    // ----------------------------------------------------------------
    public ContentAwareUntypedAgent withBoundContent(List<Content> contents) {
        this.boundContents = new ArrayList<>(contents);
        return this;
    }

    // ----------------------------------------------------------------
    // UntypedAgent contract methods
    // ----------------------------------------------------------------
    @Override
    public String getName() {
        return agentName;
    }

    @Override
    public String getOutputKey() {
        return outputKey;
    }
}