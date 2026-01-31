package com.eainde.agent;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.listener.*;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer; // Spring Boot 3 Tracer

import java.util.stream.Collectors;

public class OTelLangfuseListener implements ChatModelListener {

    private final Tracer tracer;

    public OTelLangfuseListener(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        // 1. Start a new Span.
        // Langfuse treats spans named "chat_model_execution" or similar as generations
        Span span = tracer.nextSpan().name("chat_model_execution");

        // 2. Set Critical Attributes that Langfuse looks for
        // These keys tell Langfuse: "This is an LLM call, not just a random code function"
        span.tag("gen_ai.system", "langchain4j");
        span.tag("gen_ai.request.model", context.request().model());
        span.tag("gen_ai.request.temperature", String.valueOf(context.request().parameters().temperature()));

        // 3. Log the Prompt
        String prompt = context.request().messages().stream()
                .map(this::extractContent)
                .collect(Collectors.joining("\n"));
        span.tag("gen_ai.prompt", prompt);

        span.start();

        // 4. Store span in context to close it later
        context.attributes().put("current_span", span);
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        Span span = (Span) context.requestContext().attributes().get("current_span");
        if (span != null) {
            // 5. Log the Output and Usage
            span.tag("gen_ai.completion", context.response().aiMessage().text());

            if (context.response().tokenUsage() != null) {
                span.tag("gen_ai.usage.input_tokens", String.valueOf(context.response().tokenUsage().inputTokenCount()));
                span.tag("gen_ai.usage.output_tokens", String.valueOf(context.response().tokenUsage().outputTokenCount()));
                span.tag("gen_ai.usage.total_tokens", String.valueOf(context.response().tokenUsage().totalTokenCount()));
            }

            span.end();
        }
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        Span span = (Span) context.requestContext().attributes().get("current_span");
        if (span != null) {
            span.error(context.error());
            span.end();
        }
    }

// Helper for extracting text from messages
