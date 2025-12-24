package com.eainde.agent;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.vertexai.VertexAiGeminiChatModel;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;
import java.util.List;

/**
 * A wrapper/clone of VertexAiGeminiChatModel that allows injecting custom GoogleCredentials.
 * This effectively bypasses the limitation of the standard builder.
 */
public class WifVertexAiGeminiChatModel implements ChatLanguageModel {

    private final VertexAiGeminiChatModel delegate;
    private final VertexAI vertexAi;

    public WifVertexAiGeminiChatModel(String project, String location, String modelName, GoogleCredentials credentials) {
        // 1. Initialize VertexAI with YOUR custom credentials
        // This is the part standard LangChain4j doesn't let you do
        this.vertexAi = new VertexAI.Builder()
                .setProjectId(project)
                .setLocation(location)
                .setCredentials(credentials) // <--- INJECTING YOUR AZURE-WIF CREDS
                .build();

        // 2. Initialize the GenerativeModel using the authenticated VertexAI client
        GenerativeModel generativeModel = new GenerativeModel(modelName, this.vertexAi);

        // 3. Use the "Same-Package" trick or Reflection to bridge to LangChain's implementation.
        // Since we can't easily access package-private constructors of VertexAiGeminiChatModel,
        // we will use a "Delegate" pattern if available, or we must use the library's internal classes.

        // CRITICAL: Because LangChain4j constructors are hidden, the cleanest way is
        // actually to place this class in "dev.langchain4j.model.vertexai" package
        // inside your src/main/java.

        // HOWEVER, assuming you don't want to mess with package structures,
        // we can instantiate the standard model via Reflection or standard path if available.

        // For simplicity, we will delegate to a created instance if possible,
        // or strictly rely on the package-move trick below.
        this.delegate = null; // See Step 2 below
    }

    // Proxy methods
    @Override
    public String generate(String userMessage) {
        return delegate.generate(userMessage);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return delegate.generate(messages);
    }
}
