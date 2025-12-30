package com.eainde.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.listener.*;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Decorator that adds Observability (Listeners) to ANY ChatLanguageModel.
 * Use this to wrap models that don't support listeners in their constructors.
 */
public class ObservableChatModel implements ChatLanguageModel {

    private final ChatLanguageModel delegate;
    private final List<ChatModelListener> listeners;

    public ObservableChatModel(ChatLanguageModel delegate, List<ChatModelListener> listeners) {
        this.delegate = delegate;
        this.listeners = listeners != null ? listeners : Collections.emptyList();
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        // 1. Prepare Context
        ChatModelRequest modelRequest = ChatModelRequest.builder()
                .messages(messages)
                .build();
        Map<Object, Object> attributes = new ConcurrentHashMap<>();
        ChatModelRequestContext requestContext = new ChatModelRequestContext(modelRequest, attributes);

        // 2. Notify Listeners (OnRequest)
        listeners.forEach(l -> {
            try {
                l.onRequest(requestContext);
            } catch (Exception e) {
                System.err.println("Listener onRequest failed: " + e.getMessage());
            }
        });

        try {
            // 3. Delegate to the Real Model (Vertex AI)
            Response<AiMessage> response = delegate.generate(messages);

            // 4. Notify Listeners (OnResponse)
            ChatModelResponse modelResponse = ChatModelResponse.builder()
                    .aiMessage(response.content())
                    .tokenUsage(response.tokenUsage())
                    .build();
            ChatModelResponseContext responseContext = new ChatModelResponseContext(
                    modelResponse,
                    modelRequest,
                    attributes
            );

            listeners.forEach(l -> {
                try {
                    l.onResponse(responseContext);
                } catch (Exception e) {
                    System.err.println("Listener onResponse failed: " + e.getMessage());
                }
            });

            return response;

        } catch (Exception e) {
            // 5. Notify Listeners (OnError)
            ChatModelErrorContext errorContext = new ChatModelErrorContext(
                    e,
                    modelRequest,
                    null,
                    attributes
            );
            listeners.forEach(l -> {
                try {
                    l.onError(errorContext);
                } catch (Exception ex) {
                    System.err.println("Listener onError failed: " + ex.getMessage());
                }
            });
            throw e;
        }
    }
}
