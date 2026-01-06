package com.eainde.agent;

import com.google.cloud.vertexai.VertexAI;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import java.util.Collections;
import java.util.List;

public class VertexAiFactory {

    private final VertexAI vertexAI;
    private final List<ChatModelListener> listeners;

    // Package-private constructor: Only created by AutoConfiguration
    VertexAiFactory(VertexAI vertexAI, List<ChatModelListener> listeners) {
        this.vertexAI = vertexAI;
        this.listeners = listeners != null ? listeners : Collections.emptyList();
    }

    /**
     * Start creating a new model.
     * @param modelName The mandatory model name (e.g., "gemini-1.5-pro-001")
     * @return A fluent builder to configure optional parameters.
     */
    public VertexAiModelBuilder builder(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("Model name is mandatory");
        }
        return new VertexAiModelBuilder(vertexAI, listeners, modelName);
    }
}
