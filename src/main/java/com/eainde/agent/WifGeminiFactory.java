package com.eainde.agent;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.generativeai.GenerativeModel;

/**
 * This class lives in the LangChain4j package to access package-private constructors.
 */
public class WifGeminiFactory {

    public static VertexAiGeminiChatModel create(String project, String location, String modelName, GoogleCredentials credentials) {

        // 1. Manually build the authenticated VertexAI client
        VertexAI vertexAi = new VertexAI.Builder()
                .setProjectId(project)
                .setLocation(location)
                .setCredentials(credentials) // Your AzureToGoogleCredentials
                .build();

        // 2. Create the Google GenerativeModel
        GenerativeModel generativeModel = new GenerativeModel(modelName, vertexAi);

        // 3. Call the package-private constructor of VertexAiGeminiChatModel
        // (Verify the arguments match your specific LangChain4j version)
        return new VertexAiGeminiChatModel(
                generativeModel,
                new VertexAiGeminiChatOptions(), // default options
                null, // toolCallingManager (can be null or default)
                null, // retryTemplate
                null  // observationRegistry
        );
    }
}
