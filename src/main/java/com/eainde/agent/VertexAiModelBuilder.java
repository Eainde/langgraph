package com.eainde.agent;

import com.nexus.ai.core.model.ObservableChatModel;
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.GenerationConfig;
import com.google.cloud.vertexai.api.Schema;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.vertexai.VertexAiGeminiChatModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class VertexAiModelBuilder {

    private final VertexAI vertexAI;
    private final List<ChatModelListener> listeners;
    private final String modelName;

    // --- Standard Parameters ---
    private Float temperature;
    private Integer topK;
    private Float topP;
    private Integer maxOutputTokens;
    private Integer candidateCount;

    // --- Repeatable Fields (Stop Sequences) ---
    private List<String> stopSequences;

    // --- Penalties & Determinism ---
    private Float presencePenalty;
    private Float frequencyPenalty;
    private Integer seed;

    // --- Structured Output ---
    private String responseMimeType;
    private Schema responseSchema; // Requires com.google.cloud.vertexai.api.Schema

    // --- Advanced ---
    private Boolean responseLogprobs;
    private Integer logprobs;

    VertexAiModelBuilder(VertexAI vertexAI, List<ChatModelListener> listeners, String modelName) {
        this.vertexAI = vertexAI;
        this.listeners = listeners;
        this.modelName = modelName;
    }

    // --- Core Parameters ---

    public VertexAiModelBuilder temperature(Double temperature) {
        if (temperature != null) this.temperature = temperature.floatValue();
        return this;
    }

    public VertexAiModelBuilder topK(Integer topK) {
        this.topK = topK;
        return this;
    }

    public VertexAiModelBuilder topP(Double topP) {
        if (topP != null) this.topP = topP.floatValue();
        return this;
    }

    public VertexAiModelBuilder maxOutputTokens(Integer maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
        return this;
    }

    public VertexAiModelBuilder candidateCount(Integer candidateCount) {
        this.candidateCount = candidateCount;
        return this;
    }

    // --- Stop Sequences (Handling "RepeatableField" logic) ---

    /**
     * Sets the full list of stop sequences (overwrites previous).
     */
    public VertexAiModelBuilder stopSequences(List<String> stopSequences) {
        this.stopSequences = stopSequences;
        return this;
    }

    /**
     * Adds a single stop sequence (convenience method).
     */
    public VertexAiModelBuilder addStopSequence(String stopSequence) {
        if (this.stopSequences == null) {
            this.stopSequences = new ArrayList<>();
        }
        this.stopSequences.add(stopSequence);
        return this;
    }

    /**
     * Adds multiple stop sequences (convenience method).
     */
    public VertexAiModelBuilder addStopSequences(String... stopSequences) {
        if (this.stopSequences == null) {
            this.stopSequences = new ArrayList<>();
        }
        this.stopSequences.addAll(Arrays.asList(stopSequences));
        return this;
    }

    // --- Penalties ---

    public VertexAiModelBuilder presencePenalty(Double presencePenalty) {
        if (presencePenalty != null) this.presencePenalty = presencePenalty.floatValue();
        return this;
    }

    public VertexAiModelBuilder frequencyPenalty(Double frequencyPenalty) {
        if (frequencyPenalty != null) this.frequencyPenalty = frequencyPenalty.floatValue();
        return this;
    }

    public VertexAiModelBuilder seed(Integer seed) {
        this.seed = seed;
        return this;
    }

    // --- Format & Schema ---

    public VertexAiModelBuilder responseMimeType(String responseMimeType) {
        this.responseMimeType = responseMimeType;
        return this;
    }

    public VertexAiModelBuilder responseSchema(Schema responseSchema) {
        this.responseSchema = responseSchema;
        return this;
    }

    public VertexAiModelBuilder jsonMode(boolean enable) {
        if (enable) {
            this.responseMimeType = "application/json";
        }
        return this;
    }

    // --- Logprobs (If supported by your SDK version) ---

    public VertexAiModelBuilder responseLogprobs(Boolean responseLogprobs) {
        this.responseLogprobs = responseLogprobs;
        return this;
    }

    public VertexAiModelBuilder logprobs(Integer logprobs) {
        this.logprobs = logprobs;
        return this;
    }

    // --- Build ---

    public ChatModel build() {
        // 1. Build Native Google Config
        GenerationConfig.Builder configBuilder = GenerationConfig.newBuilder();

        if (temperature != null) configBuilder.setTemperature(temperature);
        if (topK != null) configBuilder.setTopK(topK);
        if (topP != null) configBuilder.setTopP(topP);
        if (maxOutputTokens != null) configBuilder.setMaxOutputTokens(maxOutputTokens);
        if (candidateCount != null) configBuilder.setCandidateCount(candidateCount);
        if (stopSequences != null && !stopSequences.isEmpty()) {
            configBuilder.addAllStopSequences(stopSequences);
        }
        if (presencePenalty != null) configBuilder.setPresencePenalty(presencePenalty);
        if (frequencyPenalty != null) configBuilder.setFrequencyPenalty(frequencyPenalty);
        if (seed != null) configBuilder.setSeed(seed);
        if (responseMimeType != null) configBuilder.setResponseMimeType(responseMimeType);
        if (responseSchema != null) configBuilder.setResponseSchema(responseSchema);

        // Note: Check if your SDK version has these methods (they are in v1beta1)
        if (responseLogprobs != null) configBuilder.setResponseLogprobs(responseLogprobs);
        if (logprobs != null) configBuilder.setLogprobs(logprobs);

        GenerationConfig finalConfig = configBuilder.build();

        // 2. Create Model Wrapper
        @SuppressWarnings("deprecation")
        GenerativeModel generativeModel = new GenerativeModel(modelName, vertexAI);
        generativeModel.setGenerationConfig(finalConfig);

        // 3. LangChain Wrapper
        VertexAiGeminiChatModel nativeModel = new VertexAiGeminiChatModel(
                generativeModel,
                finalConfig
        );

        // 4. Observability Wrapper
        return new ObservableChatModel(nativeModel, listeners);
    }
}