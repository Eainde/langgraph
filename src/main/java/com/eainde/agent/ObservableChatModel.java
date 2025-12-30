package com.eainde.agent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decorator for LangChain4j 0.35+ ChatModel.
 * Adds observability (listeners) to any ChatModel (like VertexAI, OpenAI, etc.).
 */
public class ObservableChatModel implements ChatModel {

    private final ChatModel delegate;
    private final List<ChatModelListener> listeners;

    public ObservableChatModel(ChatModel delegate, List<ChatModelListener> listeners) {
        this.delegate = delegate;
        this.listeners = listeners != null ? listeners : Collections.emptyList();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        // 1. Prepare Context
        // In the new API, we don't need to build a request manually; we already have the ChatRequest.
        Map<Object, Object> attributes = new ConcurrentHashMap<>();
        ChatModelRequestContext requestContext = new ChatModelRequestContext(request, attributes);

        // 2. Notify Listeners (OnRequest)
        listeners.forEach(l -> {
            try {
                l.onRequest(requestContext);
            } catch (Exception e) {
                System.err.println("Listener onRequest failed: " + e.getMessage());
            }
        });

        try {
            // 3. Delegate to the Real Model
            ChatResponse response = delegate.chat(request);

            // 4. Notify Listeners (OnResponse)
            ChatModelResponseContext responseContext = new ChatModelResponseContext(
                    response,
                    request,
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
            // Note: Partial response is null here as this is a blocking call
            ChatModelErrorContext errorContext = new ChatModelErrorContext(
                    e,
                    request,
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