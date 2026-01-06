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

    private String formatMessages(List<ChatMessage> messages) {
        if (messages == null) return "[]";
        return messages.stream()
                .map(this::formatSingleMessage)
                .collect(Collectors.joining(" | "));
    }

    private String formatSingleMessage(ChatMessage message) {
        // UserMessages are special because they contain the File/Image content
        if (message instanceof UserMessage) {
            UserMessage userMsg = (UserMessage) message;
            return "USER: " + userMsg.contents().stream()
                    .map(this::formatContent)
                    .collect(Collectors.joining(" "));
        }

        // For System or AI messages, printing the text is usually safe
        return message.type() + ": " + message.text();
    }

    private String formatContent(Content content) {
        if (content instanceof TextContent) {
            return ((TextContent) content).text();
        } else if (content instanceof ImageContent) {
            // This replaces the massive base64 string with a simple tag
            return "[FILE/IMAGE CONTENT HIDDEN]";
        } else {
            return "[BINARY CONTENT HIDDEN]";
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
