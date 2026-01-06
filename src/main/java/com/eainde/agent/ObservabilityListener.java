package com.eainde.agent;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.output.TokenUsage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ObservabilityListener implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityListener.class);

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        String cleanMessages = formatMessages(requestContext.chatRequest().messages());
        // Access the raw request
        log.info("Sending request to model: {}", requestContext.chatRequest().messages());

        // You can also add custom attributes to the context to track specific traces
        requestContext.attributes().put("startTime", System.currentTimeMillis());
    }

    /**
     * Helper method to iterate through messages and hide file contents.
     */
    private String formatMessages(List<ChatMessage> messages) {
        if (messages == null) return "[]";

        return messages.stream().map(message -> {
            String type = message.type().toString();

            if (message instanceof UserMessage) {
                UserMessage userMsg = (UserMessage) message;
                // UserMessages can contain multiple "Contents" (Text + Files)
                String contentSummary = userMsg.contents().stream()
                        .map(this::formatContent)
                        .collect(Collectors.joining(" | "));
                return type + ": " + contentSummary;
            } else {
                // System and AI messages usually just have text
                return type + ": " + message.text();
            }
        }).collect(Collectors.joining("\n"));
    }

    private String formatContent(Content content) {
        if (content instanceof TextContent) {
            return ((TextContent) content).text();
        } else if (content instanceof ImageContent) {
            // Replaces the massive base64/URL string with a simple tag
            return "[Image/File Content]";
        } else {
            // Handles any other binary types (Audio, Video, etc.)
            return "[Media Content: " + content.getClass().getSimpleName() + "]";
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        long startTime = (long) responseContext.attributes().get("startTime");
        long duration = System.currentTimeMillis() - startTime;

        TokenUsage usage = responseContext.chatResponse().tokenUsage();

        log.info("Model responded in {}ms", duration);
        log.info("Token usage - Input: {}, Output: {}, Total: {}",
                usage.inputTokenCount(),
                usage.outputTokenCount(),
                usage.totalTokenCount());

        // Here you could also push metrics to Micrometer or Prometheus
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        log.error("LLM interaction failed", errorContext.error());
    }
}
