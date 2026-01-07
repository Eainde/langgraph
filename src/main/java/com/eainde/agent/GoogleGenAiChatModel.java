package com.eainde.agent;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.types.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.output.Response;

import java.time.Duration;
import java.util.*;

import static dev.langchain4j.internal.ValidationUtils.ensureNotBlank;

public class GoogleGenAiChatModel implements ChatModel {

    private final Client client;
    private final String modelName;
    private final GenerateContentConfig defaultConfig;
    private final Duration timeout; // Stored for reference or manual handling if needed

    private GoogleGenAiChatModel(Builder builder) {
        this.modelName = ensureNotBlank(builder.modelName, "modelName");
        this.defaultConfig = builder.defaultConfig;
        this.timeout = builder.timeout;

        if (builder.client != null) {
            this.client = builder.client;
        } else {
            // 1. Configure HttpOptions with Timeout
            HttpOptions.Builder httpOptions = HttpOptions.builder();
            if (builder.timeout != null) {
                // The Google GenAI SDK typically accepts timeout in milliseconds (long)
                // or Duration depending on the specific version.
                // We use toMillis() to be safe for standard Java HTTP transports.
                httpOptions.timeout(builder.timeout.toMillis());
            }

            // 2. Build Client
            Client.Builder clientBuilder = Client.builder()
                    .httpOptions(httpOptions.build()); // Apply timeout here

            if (builder.googleCredentials != null) {
                clientBuilder.credentials(builder.googleCredentials).vertexAI(true);
                if (builder.projectId != null) clientBuilder.project(builder.projectId);
                if (builder.location != null) clientBuilder.location(builder.location);
            } else if (builder.apiKey != null) {
                clientBuilder.apiKey(builder.apiKey);
            }

            this.client = clientBuilder.build();
        }
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        // Implementation remains the same...
        // The 'client' instance already carries the timeout configuration.
        // ...
        return null; // Placeholder
    }

    // --- Updated Builder ---

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Client client;
        private GoogleCredentials googleCredentials;
        private String apiKey;
        private String projectId;
        private String location;
        private String modelName;
        private GenerateContentConfig defaultConfig;

        // New Field
        private Duration timeout;

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder googleCredentials(GoogleCredentials googleCredentials) {
            this.googleCredentials = googleCredentials;
            return this;
        }

        // ... other setters ...

        public GoogleGenAiChatModel build() {
            return new GoogleGenAiChatModel(this);
        }
    }
}