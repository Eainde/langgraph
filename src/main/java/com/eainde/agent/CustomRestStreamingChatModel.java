package com.eainde.agent;

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

        public Builder maxOutputTokens(Integer maxOutputTokens)