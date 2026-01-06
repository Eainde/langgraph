package com.eainde.agent;

import com.nexus.ai.core.model.ObservableChatModel; // Your wrapper from previous steps
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.GenerationConfig;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.vertexai.VertexAiGeminiChatModel;

import java.util.List;

public class VertexAiModelBuilder {

    private final VertexAI vertexAI;
    private final List<ChatModelListener> listeners;
    private final String modelName;

    // Optional parameters with defaults
    private Float temperature;
    private Integer topK;
    private Integer maxOutputTokens;

    VertexAiModelBuilder(VertexAI vertexAI, List<ChatModelListener> listeners, String modelName) {
        this.vertexAI = vertexAI;
        this.listeners = listeners;
        this.modelName = modelName;
    }

    public VertexAiModelBuilder temperature(Double temperature) {
        if (temperature != null) this.temperature = temperature.floatValue();
        return this;
    }

    public VertexAiModelBuilder topK(Integer topK) {
        this.topK = topK;
        return this;
    }

    public VertexAiModelBuilder maxOutputTokens(Integer maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
        return this;
    }

    public ChatModel build() {
        // 1. Configure Native Google Config
        GenerationConfig.Builder configBuilder = GenerationConfig.newBuilder();
        if (temperature != null) configBuilder.setTemperature(temperature);
        if (topK != null) configBuilder.setTopK(topK);
        if (maxOutputTokens != null) configBuilder.setMaxOutputTokens(maxOutputTokens);

        // 2. Create Model Wrapper (Reusing the singleton VertexAI connection)
        @SuppressWarnings("deprecation")
        GenerativeModel generativeModel = new GenerativeModel(modelName, vertexAI);
        generativeModel.setGenerationConfig(configBuilder.build());

        // 3. LangChain Wrapper
        VertexAiGeminiChatModel nativeModel = new VertexAiGeminiChatModel(
                generativeModel,
                configBuilder.build()
        );

        // 4. Observability Wrapper
        return new ObservableChatModel(nativeModel, listeners);
    }
}
