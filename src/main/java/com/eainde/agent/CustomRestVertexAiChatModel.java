package com.eainde.agent;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.listener.*;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import okhttp3.*;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom Vertex AI Chat Model (Blocking/Synchronous) using REST.
 * Supports WIF Authentication, Builder Pattern, and Observability Listeners.
 */
public class CustomRestVertexAiChatModel implements ChatLanguageModel {

    private static final String BASE_URL_TEMPLATE =
            "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent";

    private final OkHttpClient httpClient;
    private final String fullEndpointUrl;
    private final Gson gson;
    private final GoogleCredentials credentials;
    private final Double temperature;
    private final Integer maxOutputTokens;
    private final List<ChatModelListener> listeners;

    private CustomRestVertexAiChatModel(Builder builder) {
        this.credentials = builder.credentials;
        this.temperature = builder.temperature != null ? builder.temperature : 0.7;
        this.maxOutputTokens = builder.maxOutputTokens != null ? builder.maxOutputTokens : 2048;
        this.listeners = builder.listeners != null ? builder.listeners : Collections.emptyList();
        this.gson = new GsonBuilder().create();

        if (builder.projectId == null || builder.location == null || builder.modelName == null || builder.credentials == null) {
            throw new IllegalArgumentException("projectId, location, modelName, and credentials are required");
        }

        this.fullEndpointUrl = String.format(BASE_URL_TEMPLATE, builder.location, builder.projectId, builder.location, builder.modelName);

        Interceptor authInterceptor = chain -> {
            Request original = chain.request();
            try {
                Map<String, List<String>> authHeaders = credentials.getRequestMetadata(URI.create(fullEndpointUrl));
                Request.Builder reqBuilder = original.newBuilder().header("Content-Type", "application/json");
                if (authHeaders != null) {
                    authHeaders.forEach((k, v) -> {
                        if (v != null && !v.isEmpty()) reqBuilder.header(k, v.get(0));
                    });
                }
                return chain.proceed(reqBuilder.build());
            } catch (IOException e) {
                throw new IOException("Auth failure during REST call", e);
            }
        };

        this.httpClient = new OkHttpClient.Builder()
                .addInterceptor(authInterceptor)
                .readTimeout(java.time.Duration.ofSeconds(60))
                .build();
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        // 1. Prepare Request Context
        ChatModelRequest modelRequest = ChatModelRequest.builder()
                .messages(messages)
                .build();

        Map<Object, Object> attributes = new ConcurrentHashMap<>();
        ChatModelRequestContext requestContext = new ChatModelRequestContext(modelRequest, attributes);

        // 2. Notify Listeners (OnRequest)
        if (listeners != null) {
            listeners.forEach(l -> {
                try {
                    l.onRequest(requestContext);
                } catch (Exception e) {
                    System.err.println("Listener onRequest failed: " + e.getMessage());
                }
            });
        }

        try {
            // 3. Prepare HTTP Request
            GeminiRequest requestPayload = createRequest(messages);
            String jsonBody = gson.toJson(requestPayload);

            Request request = new Request.Builder()
                    .url(fullEndpointUrl)
                    .post(RequestBody.create(jsonBody, MediaType.get("application/json")))
                    .build();

            // 4. Execute Sync
            try (okhttp3.Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "No body";
                    throw new RuntimeException("Vertex AI call failed: " + response.code() + " - " + errorBody);
                }

                String responseBody = response.body().string();
                GeminiResponse geminiResponse = gson.fromJson(responseBody, GeminiResponse.class);

                // Handle safety blocks
                if (geminiResponse.candidates == null || geminiResponse.candidates.isEmpty()) {
                    throw new RuntimeException("Vertex AI returned no candidates (likely Safety Block).");
                }

                String text = geminiResponse.candidates.get(0).content.parts.get(0).text;

                TokenUsage usage = new TokenUsage(0, 0);
                if (geminiResponse.usageMetadata != null) {
                    usage = new TokenUsage(
                            geminiResponse.usageMetadata.promptTokenCount,
                            geminiResponse.usageMetadata.candidatesTokenCount
                    );
                }

                AiMessage aiMessage = AiMessage.from(text);
                Response<AiMessage> lcResponse = Response.from(aiMessage, usage);

                // 5. Notify Listeners (OnResponse)
                ChatModelResponse modelResponse = ChatModelResponse.builder()
                        .aiMessage(aiMessage)
                        .tokenUsage(usage)
                        .build();

                ChatModelResponseContext responseContext = new ChatModelResponseContext(
                        modelResponse,
                        modelRequest,
                        attributes
                );

                if (listeners != null) {
                    listeners.forEach(l -> {
                        try {
                            l.onResponse(responseContext);
                        } catch (Exception e) {
                            System.err.println("Listener onResponse failed: " + e.getMessage());
                        }
                    });
                }

                return lcResponse;
            }

        } catch (Exception e) {
            // 6. Notify Listeners (OnError)
            notifyError(listeners, requestContext, e, null, attributes);
            throw new RuntimeException("Failed to generate response", e);
        }
    }

    private void notifyError(List<ChatModelListener> listeners,
                             ChatModelRequestContext requestContext,
                             Throwable error,
                             ChatModelResponse partialResponse,
                             Map<Object, Object> attributes) {

        ChatModelErrorContext errorContext = new ChatModelErrorContext(
                error,
                requestContext.request(),
                partialResponse,
                attributes
        );

        if (listeners != null) {
            listeners.forEach(l -> {
                try {
                    l.onError(errorContext);
                } catch (Exception e) {
                    System.err.println("Listener onError failed: " + e.getMessage());
                }
            });
        }
    }

    private GeminiRequest createRequest(List<ChatMessage> messages) {
        List<GeminiContent> contents = new ArrayList<>();
        for (ChatMessage msg : messages) {
            String role = (msg instanceof UserMessage) ? "user" : "model";
            contents.add(new GeminiContent(role, List.of(new GeminiPart(msg.text()))));
        }
        GeminiGenerationConfig config = new GeminiGenerationConfig(temperature, maxOutputTokens);
        return new GeminiRequest(contents, config);
    }

    // --- Builder Pattern ---
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String projectId;
        private String location;
        private String modelName;
        private GoogleCredentials credentials;
        private Double temperature;
        private Integer maxOutputTokens;
        private List<ChatModelListener> listeners;

        public Builder projectId(String projectId) { this.projectId = projectId; return this; }
        public Builder location(String location) { this.location = location; return this; }
        public Builder modelName(String modelName) { this.modelName = modelName; return this; }
        public Builder credentials(GoogleCredentials credentials) { this.credentials = credentials; return this; }
        public Builder temperature(Double temperature) { this.temperature = temperature; return this; }
        public Builder maxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; return this; }
        public Builder listeners(List<ChatModelListener> listeners) { this.listeners = listeners; return this; }

        public CustomRestVertexAiChatModel build() {
            return new CustomRestVertexAiChatModel(this);
        }
    }

    // --- DTOs ---
    private record GeminiRequest(List<GeminiContent> contents, GeminiGenerationConfig generationConfig) {}
    private record GeminiContent(String role, List<GeminiPart> parts) {}
    private record GeminiPart(String text) {}
    private record GeminiGenerationConfig(Double temperature, Integer maxOutputTokens) {}
    private static class GeminiResponse {
        List<Candidate> candidates;
        UsageMetadata usageMetadata;
        static class Candidate { Content content; }
        static class Content { List<GeminiPart> parts; }
        static class UsageMetadata { int promptTokenCount; int candidatesTokenCount; }
    }
}