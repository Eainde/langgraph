package com.eainde.agent;

import com.google.auth.oauth2.OAuth2Credentials;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import okhttp3.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CustomVertexAiChatModel implements ChatLanguageModel {

    private static final String BASE_URL_TEMPLATE =
            "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent";

    private final OkHttpClient httpClient;
    private final String fullEndpointUrl;
    private final Gson gson;
    private final Double temperature;
    private final Integer maxOutputTokens;

    // Use the abstract OAuth2Credentials type to accept your AzureToGoogleCredentials
    private final OAuth2Credentials credentials;

    public CustomVertexAiChatModel(String projectId,
                                   String location,
                                   String modelName,
                                   OAuth2Credentials credentials, // <--- Injected here
                                   Double temperature,
                                   Integer maxOutputTokens) {

        this.credentials = credentials;
        this.fullEndpointUrl = String.format(BASE_URL_TEMPLATE, location, projectId, location, modelName);
        this.temperature = temperature != null ? temperature : 0.7;
        this.maxOutputTokens = maxOutputTokens != null ? maxOutputTokens : 2048;
        this.gson = new GsonBuilder().create();

        // --- AUTH INTERCEPTOR ---
        Interceptor authInterceptor = chain -> {
            Request original = chain.request();

            try {
                // KEY STEP: This method checks if the current cached token is valid.
                // If expired, it calls your AzureToGoogleCredentials.refreshAccessToken() automatically.
                // It returns the map: {"Authorization": ["Bearer <token>"]}
                Map<String, List<String>> authHeaders = credentials.getRequestMetadata(URI.create(fullEndpointUrl));

                Request.Builder builder = original.newBuilder()
                        .header("Content-Type", "application/json");

                // Apply headers from the credentials object
                if (authHeaders != null) {
                    authHeaders.forEach((k, v) -> {
                        if (v != null && !v.isEmpty()) {
                            builder.header(k, v.get(0));
                        }
                    });
                }

                return chain.proceed(builder.build());
            } catch (IOException e) {
                throw new IOException("Failed to refresh or apply credentials via AzureToGoogleCredentials", e);
            }
        };

        this.httpClient = new OkHttpClient.Builder()
                .addInterceptor(authInterceptor)
                .build();
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        try {
            GeminiRequest requestPayload = createRequest(messages);
            String jsonBody = gson.toJson(requestPayload);

            RequestBody body = RequestBody.create(jsonBody, MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(fullEndpointUrl)
                    .post(body)
                    .build();

            try (okhttp3.Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "No body";
                    throw new RuntimeException("Vertex AI call failed: " + response.code() + " - " + errorBody);
                }

                String responseBody = response.body().string();
                GeminiResponse geminiResponse = gson.fromJson(responseBody, GeminiResponse.class);

                if (geminiResponse.candidates == null || geminiResponse.candidates.isEmpty()) {
                    // Handle safety blocks where candidates are empty
                    return Response.from(AiMessage.from("Blocked by safety filters or empty response."));
                }

                String text = geminiResponse.candidates.get(0).content.parts.get(0).text;

                TokenUsage usage = null;
                if (geminiResponse.usageMetadata != null) {
                    usage = new TokenUsage(
                            geminiResponse.usageMetadata.promptTokenCount,
                            geminiResponse.usageMetadata.candidatesTokenCount
                    );
                }

                return Response.from(AiMessage.from(text), usage);
            }
        } catch (IOException e) {
            throw new RuntimeException("Network error calling Vertex AI", e);
        }
    }

    private GeminiRequest createRequest(List<ChatMessage> messages) {
        List<GeminiContent> contents = new ArrayList<>();
        // Simple mapping (Same as previous example)
        for (ChatMessage msg : messages) {
            String role = (msg instanceof UserMessage) ? "user" : "model";
            contents.add(new GeminiContent(role, List.of(new GeminiPart(msg.text()))));
        }
        GeminiGenerationConfig config = new GeminiGenerationConfig(temperature, maxOutputTokens);
        return new GeminiRequest(contents, config);
    }

    // --- Inner DTOs (Same as previous) ---
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
