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
        // Access the raw request
        log.info("Sending request to model: {}", requestContext.chatRequest().messages());

        // You can also add custom attributes to the context to track specific traces
        requestContext.attributes().put("startTime", System.currentTimeMillis());
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
