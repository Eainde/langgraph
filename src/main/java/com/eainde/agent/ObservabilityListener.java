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
        if (message instanceof UserMessage) {
            UserMessage userMsg = (UserMessage) message;
            // Iterate over contents to strip out images/files but keep text
            return "USER: " + userMsg.contents().stream()
                    .map(this::formatContent)
                    .collect(Collectors.joining(" "));
        }
        else if (message instanceof SystemMessage) {
            // CASTING to SystemMessage to access .text()
            return "SYSTEM: " + ((SystemMessage) message).text();
        }
        else if (message instanceof AiMessage) {
            // CASTING to AiMessage to access .text()
            return "AI: " + ((AiMessage) message).text();
        }
        else {
            // Fallback for tools or other unknown types
            return message.type() + ": [Complex/Tool Message]";
        }
    }

    private String formatContent(Content content) {
        if (content instanceof TextContent) {
            String text = ((TextContent) content).text();

            // CHECK: Is this text actually a stringified UserMessage?
            if (text.contains("UserMessage {") && text.contains("base64Data")) {
                return extractCleanText(text);
            }
            return text;
        } else if (content instanceof ImageContent) {
            return "[FILE/IMAGE CONTENT HIDDEN]";
        } else {
            return "[BINARY CONTENT HIDDEN]";
        }
    }

    private String extractCleanText(String rawText) {
        // 1. Try to extract the 'name' field, as that seems to hold your real prompt
        Matcher matcher = NESTED_NAME_PATTERN.matcher(rawText);
        if (matcher.find()) {
            return matcher.group(1); // Returns "Can you read the attached file..."
        }

        // 2. Fallback: If name isn't found, just strip the base64 data from the string
        return BASE64_PATTERN.matcher(rawText).replaceAll("base64Data = [HIDDEN]");
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
