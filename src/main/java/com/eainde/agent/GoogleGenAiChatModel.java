package com.eainde.agent;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.types.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

import static dev.langchain4j.internal.Utils.isNullOrEmpty;
import static dev.langchain4j.internal.ValidationUtils.ensureNotBlank;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

public class GoogleGenAiChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(GoogleGenAiChatModel.class);

    private final Client client;
    private final String modelName;
    private final Integer maxRetries;
    private final Boolean logRequests;
    private final Boolean logResponses;
    private final List<ChatModelListener> listeners;
    private final ChatRequestParameters defaultRequestParameters;
    private final Map<String, String> safetySettings;
    private final boolean googleSearchEnabled;
    private final List<String> allowedFunctionNames;
    private final ToolConfig toolConfig;

    private GoogleGenAiChatModel(Builder builder) {
        this.modelName = ensureNotBlank(builder.modelName, "modelName");
        this.maxRetries = builder.maxRetries == null ? 3 : builder.maxRetries;
        this.logRequests = builder.logRequests != null && builder.logRequests;
        this.logResponses = builder.logResponses != null && builder.logResponses;
        this.listeners = builder.listeners == null ? emptyList() : new ArrayList<>(builder.listeners);
        this.googleSearchEnabled = builder.googleSearch != null && builder.googleSearch;
        this.allowedFunctionNames = builder.allowedFunctionNames;
        this.toolConfig = builder.toolConfig;

        this.safetySettings = new HashMap<>();
        if (builder.safetySettings != null) {
            builder.safetySettings.forEach((k, v) -> this.safetySettings.put(k.toString(), v.toString()));
        }

        if (builder.client != null) {
            this.client = builder.client;
        } else {
            HttpOptions.Builder httpOptions = HttpOptions.builder();
            if (builder.timeout != null) {
                // Fix #8: Cast to long (standard SDK) or int if your specific version demands it
                httpOptions.timeout(builder.timeout.toMillis());
            }

            Client.Builder clientBuilder = Client.builder().httpOptions(httpOptions.build());

            if (builder.googleCredentials != null) {
                clientBuilder.credentials(builder.googleCredentials);
                clientBuilder.vertexAI(true);
                if (builder.projectId != null) clientBuilder.project(builder.projectId);
                if (builder.location != null) clientBuilder.location(builder.location);
            } else if (builder.apiKey != null) {
                clientBuilder.apiKey(builder.apiKey);
            }
            this.client = clientBuilder.build();
        }

        this.defaultRequestParameters = DefaultChatRequestParameters.builder()
                .temperature(builder.temperature)
                .maxOutputTokens(builder.maxOutputTokens)
                .topP(builder.topP)
                .topK(builder.topK)
                .stopSequences(builder.stopSequences)
                .build();
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        ChatRequestParameters parameters = chatRequest.parameters();
        List<ChatMessage> messages = chatRequest.messages();

        // 1. Map Messages
        List<Content> contents = new ArrayList<>();
        StringBuilder systemInstruction = new StringBuilder();

        for (ChatMessage message : messages) {
            if (message instanceof SystemMessage) {
                if (systemInstruction.length() > 0) systemInstruction.append("\n");
                systemInstruction.append(message.text());
            } else {
                contents.add(GoogleGenAiMapper.toContent(message));
            }
        }

        // 2. Build Config
        GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder();

        if (parameters.temperature() != null) configBuilder.temperature(parameters.temperature().floatValue());
        if (parameters.topP() != null) configBuilder.topP(parameters.topP().floatValue());

        // Fix #8: Explicit null check and integer cast for TopK
        if (parameters.topK() != null) configBuilder.topK(parameters.topK());

        if (parameters.maxOutputTokens() != null) configBuilder.maxOutputTokens(parameters.maxOutputTokens());
        if (parameters.stopSequences() != null) configBuilder.stopSequences(parameters.stopSequences());

        if (!safetySettings.isEmpty()) {
            List<SafetySetting> safetyList = new ArrayList<>();
            safetySettings.forEach((