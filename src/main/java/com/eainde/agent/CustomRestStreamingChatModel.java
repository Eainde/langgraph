package com.eainde.agent.model;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.listener.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom Vertex AI Streaming Chat Model using REST (OkHttp).
 * Supports Builder pattern for easy configuration.
 */
public class CustomRestStreamingChatModel implements StreamingChatLanguageModel {

    private static final String BASE_URL_TEMPLATE =
            "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:streamGenerateContent?alt=sse";

    private final OkHttpClient httpClient;
    private final String fullEndpointUrl;
    private final Gson gson;
    private final GoogleCredentials credentials;
    private final Double temperature;
    private final Integer maxOutputTokens;
    private final String modelName;
    private final List<ChatModelListener> listeners;

    // Private constructor to force usage of Builder
    private CustomRestStreamingChatModel(Builder builder) {
        this.credentials = builder.credentials;
        this.modelName = builder.modelName;
        this.temperature = builder.temperature != null ? builder.temperature : 0.7;
        this.maxOutputTokens = builder.maxOutputTokens != null ? builder.maxOutputTokens : 2048;
        this.listeners = builder.listeners != null ? builder.listeners : Collections.emptyList();
        this.gson = new GsonBuilder().create();

        // Validate required fields
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

    // --- Standard Builder Factory ---
    public static Builder builder() {
        return new Builder();
    }

    // --- Builder Class ---
    public static class Builder {
        private String projectId;
        private String location;
        private String modelName;
        private GoogleCredentials credentials;
        private Double temperature;
        private Integer maxOutputTokens;
        private List<ChatModelListener> listeners;

        public Builder projectId(String projectId) {
            this.projectId = projectId;
            return this;
        }

        public Builder location(String location) {
            this.location = location;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder credentials(GoogleCredentials credentials) {
            this.credentials = credentials;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxOutputTokens(Integer maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
            return this;
        }

        public Builder listeners(List<ChatModelListener> listeners) {
            this.listeners = listeners;
            return this;
        }

        // Helper to add a single listener easily
        public Builder addListener(ChatModelListener listener) {
            if (this.listeners == null) {
                this.listeners = new ArrayList<>();
            }
            this.listeners.add(listener);
            return this;
        }

        public CustomRestStreamingChatModel build() {
            return new CustomRestStreamingChatModel(this);
        }
    }

    @Override
    public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
        // ... (Same implementation as previous response) ...
        // 1. Prepare Request Context
        ChatRequest chatRequest = ChatRequest.builder().messages(messages).build();
        Map<Object, Object> attributes = new ConcurrentHashMap<>();
        ChatModelRequestContext requestContext = new ChatModelRequestContext(chatRequest, attributes);

        // 2. Notify Listeners (OnRequest)
        listeners.forEach(l -> { try { l.onRequest(requestContext); } catch (Exception e) {} });

        GeminiRequest payload = createRequest(messages);
        String jsonBody = gson.toJson(payload);

        Request request = new Request.Builder()
                .url(fullEndpointUrl)
                .post(RequestBody.create(jsonBody, MediaType.get("application/json")))
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                notifyError(listeners, requestContext, e, null, attributes);
                handler.onError(e);
            }

            @Override
            public void onResponse(Call call, okhttp3.Response response) {
                try {
                    if (!response.isSuccessful()) {
                        String err = response.body() != null ? response.body().string() : "Unknown";
                        Throwable t = new RuntimeException("Vertex REST Error: " + response.code() + " " + err);
                        notifyError(listeners, requestContext, t, null, attributes);
                        handler.onError(t);
                        return;
                    }

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream()))) {
                        String line;
                        StringBuilder fullContent = new StringBuilder();
                        int inputTokens = 0, outputTokens = 0;

                        while ((line = reader.readLine()) != null) {
                            if (!line.startsWith("data: ")) continue;
                            String data = line.substring(6).trim();
                            if (data.equals("[DONE]")) break;

                            try {
                                GeminiResponse part = gson.fromJson(data, GeminiResponse.class);
                                if (part.usageMetadata != null) {
                                    inputTokens = part.usageMetadata.promptTokenCount;
                                    outputTokens = part.usageMetadata.candidatesTokenCount;
                                }
                                if (part.candidates != null && !part.candidates.isEmpty()) {
                                    String text = part.candidates.get(0).content.parts.get(0).text;
                                    if (text != null) {
                                        handler.onNext(text);
                                        fullContent.append(text);
                                    }
                                }
                            } catch (Exception ignored) {}
                        }

                        // Build Final Response
                        TokenUsage usage = new TokenUsage(inputTokens, outputTokens);
                        AiMessage aiMessage = AiMessage.from(fullContent.toString());
                        Response<AiMessage> lcResponse = Response.from(aiMessage, usage);

                        // Notify Listeners (OnResponse)
                        ChatResponse chatResponse = ChatResponse.builder().aiMessage(aiMessage).tokenUsage(usage).build();
                        ChatModelResponseContext respCtx = new ChatModelResponseContext(chatResponse, requestContext.chatRequest(), attributes);
                        listeners.forEach(l -> { try { l.onResponse(respCtx); } catch (Exception e) {} });

                        handler.onComplete(lcResponse);
                    }
                } catch (Exception e) {
                    notifyError(listeners, requestContext, e, null, attributes);
                    handler.onError(e);
                }
            }
        });
    }

    private void notifyError(List<ChatModelListener> listeners, ChatModelRequestContext ctx, Throwable t, ChatResponse resp, Map<Object, Object> attrs) {
        ChatModelErrorContext errCtx = new ChatModelErrorContext(t, ctx.chatRequest(), resp, attrs);
        listeners.forEach(l -> { try { l.onError(errCtx); } catch (Exception e) {} });
    }

    private GeminiRequest createRequest(List<ChatMessage> messages) {
        List<GeminiContent> contents = new ArrayList<>();
        for (ChatMessage msg : messages) {
            String role = (msg instanceof UserMessage) ? "user" : "model";
            contents.add(new GeminiContent(role, List.of(new GeminiPart(msg.text()))));
        }
        return new GeminiRequest(contents, new GeminiGenerationConfig(temperature, maxOutputTokens));
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