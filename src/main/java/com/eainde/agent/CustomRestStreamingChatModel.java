package com.eainde.agent;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CustomRestStreamingChatModel implements StreamingChatLanguageModel {

    // Using the REST endpoint instead of gRPC
    private static final String BASE_URL_TEMPLATE =
            "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:streamGenerateContent?alt=sse";

    private final OkHttpClient httpClient;
    private final String fullEndpointUrl;
    private final Gson gson;
    private final GoogleCredentials credentials;
    private final Double temperature;
    private final Integer maxOutputTokens;

    public CustomRestStreamingChatModel(String projectId,
                                        String location,
                                        String modelName,
                                        GoogleCredentials credentials,
                                        Double temperature,
                                        Integer maxOutputTokens) {
        this.credentials = credentials;
        this.temperature = temperature != null ? temperature : 0.7;
        this.maxOutputTokens = maxOutputTokens != null ? maxOutputTokens : 2048;
        this.gson = new GsonBuilder().create();
        this.fullEndpointUrl = String.format(BASE_URL_TEMPLATE, location, projectId, location, modelName);

        // Interceptor to inject WIF Token via your custom credentials
        Interceptor authInterceptor = chain -> {
            Request original = chain.request();
            try {
                // This triggers your Azure -> WIF flow automatically
                Map<String, List<String>> authHeaders = credentials.getRequestMetadata(URI.create(fullEndpointUrl));
                Request.Builder builder = original.newBuilder().header("Content-Type", "application/json");
                if (authHeaders != null) {
                    authHeaders.forEach((k, v) -> {
                        if (v != null && !v.isEmpty()) builder.header(k, v.get(0));
                    });
                }
                return chain.proceed(builder.build());
            } catch (IOException e) {
                throw new IOException("Auth failure during REST call", e);
            }
        };

        this.httpClient = new OkHttpClient.Builder()
                .addInterceptor(authInterceptor)
                .readTimeout(java.time.Duration.ofSeconds(60)) // Long timeout for streaming
                .build();
    }

    @Override
    public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
        GeminiRequest payload = createRequest(messages);
        String jsonBody = gson.toJson(payload);

        Request request = new Request.Builder()
                .url(fullEndpointUrl)
                .post(RequestBody.create(jsonBody, MediaType.get("application/json")))
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                handler.onError(e);
            }

            @Override
            public void onResponse(Call call, okhttp3.Response response) {
                try {
                    if (!response.isSuccessful()) {
                        String err = response.body() != null ? response.body().string() : "Unknown";
                        handler.onError(new RuntimeException("Vertex REST Error: " + response.code() + " " + err));
                        return;
                    }

                    // Parse Server-Sent Events (SSE)
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

                                // Token Usage (often in the last chunk)
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
                        handler.onComplete(Response.from(AiMessage.from(fullContent.toString()), new TokenUsage(inputTokens, outputTokens)));
                    }
                } catch (Exception e) {
                    handler.onError(e);
                }
            }
        });
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