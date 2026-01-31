package com.eainde.agent;

import com.langfuse.LangfuseClient;
import com.langfuse.model.CreateGenerationRequest;
import com.langfuse.model.UpdateGenerationRequest;
import dev.langchain4j.data.message.*; // Import specific subtypes
import dev.langchain4j.model.chat.listener.*;
import io.micrometer.tracing.Tracer;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class LangfuseChatModelListener implements ChatModelListener {

    private final LangfuseClient langfuse;
    private final Tracer tracer;

    public LangfuseChatModelListener(LangfuseClient langfuse, Tracer tracer) {
        this.langfuse = langfuse;
        this.tracer = tracer;
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        // 1. Get Spring Trace ID
        String traceId = (tracer.currentTraceContext() != null && tracer.currentTraceContext().context() != null)
                ? tracer.currentTraceContext().context().traceId()
                : UUID.randomUUID().toString();

        String generationId = UUID.randomUUID().toString();
        context.attributes().put("langfuse_generation_id", generationId);

        // 2. Correctly Extract Text from the Message List
        String prompt = context.request().messages().stream()
                .map(this::extractContent) // <--- Use the helper method here
                .collect(Collectors.joining("\n"));

        // 3. Send to Langfuse
        langfuse.createGeneration(CreateGenerationRequest.builder()
                .id(generationId)
                .traceId(traceId)
                .name("chat_model_execution")
                .model(context.request().model())
                .modelParameters(Map.of("temperature", String.valueOf(context.request().parameters().temperature())))
                .input(prompt)
                .startTime(Instant.now())
                .build()
        );
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        String generationId = (String) context.requestContext().attributes().get("langfuse_generation_id");
        if (generationId == null) return;

        // Extract response text safely
        String responseText = context.response().aiMessage().text();
        if (responseText == null) {
            // Handle Tool Execution Requests (Function Calling)
            if (context.response().aiMessage().hasToolExecutionRequests()) {
                responseText = "Tool Call: " + context.response().aiMessage().toolExecutionRequests().toString();
            } else {
                responseText = "";
            }
        }

        langfuse.updateGeneration(UpdateGenerationRequest.builder()
                .id(generationId)
                .output(responseText)
                .endTime(Instant.now())
                .usage(com.langfuse.model.Usage.builder()
                        .input(context.response().tokenUsage().inputTokenCount())
                        .output(context.response().tokenUsage().outputTokenCount())
                        .total(context.response().tokenUsage().totalTokenCount())
                        .build())
                .build()
        );
        langfuse.flush();
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        String generationId = (String) context.requestContext().attributes().get("langfuse_generation_id");
        if (generationId == null) return;

        langfuse.updateGeneration(UpdateGenerationRequest.builder()
                .id(generationId)
                .endTime(Instant.now())
                .level(com.langfuse.model.Level.ERROR)
                .statusMessage(context.error().getMessage())
                .build()
        );
        langfuse.flush();
    }

    // Helper method to handle the Enum types
    private String extractContent(ChatMessage message) {
        if (message instanceof UserMessage userMsg) {
            // UserMessages can now be a list of Text + Images
            return userMsg.contents().stream()
                    .map(content -> {
                        if (content instanceof TextContent text) {
                            return text.text();
                        } else if (content instanceof ImageContent image) {
                            return "[Image: " + image.detailLevel() + "]"; // Placeholder for images
                        } else {
                            return content.toString();
                        }
                    })
                    .collect(Collectors.joining("\n"));

        } else if (message instanceof AiMessage aiMsg) {
            if (aiMsg.toolExecutionRequests() != null && !aiMsg.toolExecutionRequests().isEmpty()) {
                return "Tool Call: " + aiMsg.toolExecutionRequests();
            }
            return aiMsg.text(); // Might be null if it's a pure tool call

        } else if (message instanceof SystemMessage sysMsg) {
            return sysMsg.text();

        } else if (message instanceof ToolExecutionResultMessage toolMsg) {
            return "Tool Result (" + toolMsg.toolName() + "): " + toolMsg.text();
        }

        return message.toString();
    }
}