package com.eainde.agent;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decorator for LangChain4j 0.35+ StreamingChatModel.
 * Adds observability (listeners) to streaming models.
 */
public class ObservableStreamingChatModel implements StreamingChatModel {

    private final StreamingChatModel delegate;
    private final List<ChatModelListener> listeners;

    public ObservableStreamingChatModel(StreamingChatModel delegate, List<ChatModelListener> listeners) {
        this.delegate = delegate;
        this.listeners = listeners != null ? listeners : Collections.emptyList();
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        Map<Object, Object> attributes = new ConcurrentHashMap<>();

        // 1. Prepare Context (Using the Enum as discovered in your screenshot)
        ChatModelRequestContext requestContext = new ChatModelRequestContext(
                request,
                ModelProvider.GOOGLE_VERTEX_AI_GEMINI,
                attributes
        );

        // 2. Notify Listeners (OnRequest)
        listeners.forEach(l -> {
            try {
                l.onRequest(requestContext);
            } catch (Exception e) {
                System.err.println("Listener onRequest failed: " + e.getMessage());
            }
        });

        // 3. Delegate with a Wrapper Handler to intercept events
        delegate.chat(request, new StreamingChatResponseHandler() {

            @Override
            public void onPartialResponse(String partialResponse) {
                // Pass throughput directly to the user (listeners usually don't care about chunks)
                handler.onPartialResponse(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                // 4. Notify Listeners (OnResponse)
                // The underlying model has already assembled the full response for us!
                ChatModelResponseContext responseContext = new ChatModelResponseContext(
                        completeResponse,
                        requestContext, // Some versions take requestContext, others take request
                        attributes
                );

                // Note: If your version's constructor is (ChatResponse, ChatRequest, Map), use:
                // new ChatModelResponseContext(completeResponse, request, attributes);

                listeners.forEach(l -> {
                    try {
                        l.onResponse(responseContext);
                    } catch (Exception e) {
                        System.err.println("Listener onResponse failed: " + e.getMessage());
                    }
                });

                // Forward to original handler
                handler.onCompleteResponse(completeResponse);
            }

            @Override
            public void onError(Throwable error) {
                // 5. Notify Listeners (OnError)
                ChatModelErrorContext errorContext = new ChatModelErrorContext(
                        error,
                        request,
                        null, // No partial response available in error case usually
                        attributes
                );

                listeners.forEach(l -> {
                    try {
                        l.onError(errorContext);
                    } catch (Exception e) {
                        System.err.println("Listener onError failed: " + e.getMessage());
                    }
                });

                // Forward to original handler
                handler.onError(error);
            }
        });
    }
}
