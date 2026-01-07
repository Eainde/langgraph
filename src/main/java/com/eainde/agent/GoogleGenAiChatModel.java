package com.eainde.agent;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.types.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.*;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static dev.langchain4j.internal.Utils.isNullOrEmpty;
import static dev.langchain4j.internal.ValidationUtils.ensureNotBlank;
import static dev.langchain4j.model.chat.Capability.RESPONSE_FORMAT_JSON;
import static dev.langchain4j.model.chat.Capability.TOOL_EXECUTION;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

/**
 * A LangChain4j ChatModel implementation using the modern Google Gen AI SDK (com.google.genai).
 * Supports both Gemini Developer API and Vertex AI.
 */
public class GoogleGenAiChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(GoogleGenAiChatModel.class);

    private final Client client;
    private final String modelName;
    private final GenerateContentConfig defaultConfig;
    private final Integer maxRetries;
    private final Boolean logRequests;
    private final Boolean logResponses;
    private final List<ChatModelListener> listeners;
    private final Set<Capability> supportedCapabilities;

    // Advanced Configuration Fields
    private final Tool googleSearchTool;
    private final ToolConfig toolConfig;
    private final List<String> allowedFunctionNames;

    private GoogleGenAiChatModel(Builder builder) {
        this.modelName = ensureNotBlank(builder.modelName, "modelName");
        this.maxRetries = builder.maxRetries == null ? 3 : builder.maxRetries;
        this.logRequests = builder.logRequests != null && builder.logRequests;
        this.logResponses = builder.logResponses != null && builder.logResponses;
        this.listeners = builder.listeners == null ? emptyList() : new ArrayList<>(builder.listeners);
        this.allowedFunctionNames = builder.allowedFunctionNames;
        this.toolConfig = builder.toolConfig;

        // Capabilities
        Set<Capability> capabilities = new HashSet<>();
        capabilities.add(RESPONSE_FORMAT_JSON);
        capabilities.add(TOOL_EXECUTION);
        this.supportedCapabilities = Collections.unmodifiableSet(capabilities);

        // 1. Initialize Client (Handling Auth & Timeouts)
        if (builder.client != null) {
            this.client = builder.client;
        } else {
            HttpOptions.Builder httpOptions = HttpOptions.builder();
            if (builder.timeout != null) {
                httpOptions.timeout(builder.timeout.toMillis());
            }

            Client.Builder clientBuilder = Client.builder()
                    .httpOptions(httpOptions.build());

            if (builder.googleCredentials != null) {
                clientBuilder.credentials(builder.googleCredentials);
                clientBuilder.vertexAI(true); // Implicitly enable Vertex AI when using Creontials
                if (builder.projectId != null) clientBuilder.project(builder.projectId);
                if (builder.location != null) clientBuilder.location(builder.location);
            } else if (builder.apiKey != null) {
                clientBuilder.apiKey(builder.apiKey);
            }

            this.client = clientBuilder.build();
        }

        // 2. Initialize Google Search Tool
        if (builder.googleSearch != null && builder.googleSearch) {
            this.googleSearchTool = Tool.builder()
                    .googleSearch(GoogleSearch.builder().build())
                    .build();
        } else {
            this.googleSearchTool = null;
        }

        // 3. Initialize Default Configuration
        GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder();
        if (builder.temperature != null) configBuilder.temperature(builder.temperature.floatValue());
        if (builder.topP != null) configBuilder.topP(builder.topP.floatValue());
        if (builder.topK != null) configBuilder.topK(builder.topK);
        if (builder.maxOutputTokens != null) configBuilder.maxOutputTokens(builder.maxOutputTokens);
        if (builder.stopSequences != null) configBuilder.stopSequences(builder.stopSequences);

        if (builder.safetySettings != null) {
            List<SafetySetting> safetyList = new ArrayList<>();
            builder.safetySettings.forEach((category, threshold) ->
                    safetyList.add(SafetySetting.builder()
                            .category(category.toString())
                            .threshold(threshold.toString())
                            .build())
            );
            configBuilder.safetySettings(safetyList);
        }

        this.defaultConfig = configBuilder.build();
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return generate(messages, (List<ToolSpecification>) null);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        return generate(messages, toolSpecifications, null);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, ToolSpecification toolSpecification) {
        return generate(messages, singletonList(toolSpecification), toolSpecification);
    }

    private Response<AiMessage> generate(List<ChatMessage> messages,
                                         List<ToolSpecification> toolSpecifications,
                                         ToolSpecification toolChoice) {

        // --- 1. Map Input Messages ---
        List<Content> contents = new ArrayList<>();
        StringBuilder systemInstruction = new StringBuilder();

        for (ChatMessage message : messages) {
            if (message instanceof SystemMessage) {
                if (systemInstruction.length() > 0) systemInstruction.append("\n");
                systemInstruction.append(((SystemMessage) message).text());
            } else {
                contents.add(GoogleGenAiMapper.toContent(message));
            }
        }

        // --- 2. Build Request Config ---
        GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder().from(defaultConfig);

        if (systemInstruction.length() > 0) {
            configBuilder.systemInstruction(Content.builder()
                    .parts(singletonList(Part.builder().text(systemInstruction.toString()).build()))
                    .build());
        }

        // --- 3. Handle Tools (Function Calling & Grounding) ---
        List<Tool> requestTools = new ArrayList<>();

        // Add Google Search if enabled
        if (this.googleSearchTool != null) {
            requestTools.add(this.googleSearchTool);
        }

        // Add Function Declarations
        if (!isNullOrEmpty(toolSpecifications)) {
            List<FunctionDeclaration> functionDecls = toolSpecifications.stream()
                    .map(GoogleGenAiMapper::toFunctionDeclaration)
                    .collect(Collectors.toList());

            requestTools.add(Tool.builder()
                    .functionDeclarations(functionDecls)
                    .build());

            // Determine Tool Config (Mode)
            if (toolChoice != null) {
                // Force specific tool
                configBuilder.toolConfig(ToolConfig.builder()
                        .functionCallingConfig(FunctionCallingConfig.builder()
                                .mode("ANY")
                                .allowedFunctionNames(singletonList(toolChoice.name()))
                                .build())
                        .build());
            } else if (!isNullOrEmpty(allowedFunctionNames)) {
                // Force specific set of tools configured in Builder
                configBuilder.toolConfig(ToolConfig.builder()
                        .functionCallingConfig(FunctionCallingConfig.builder()
                                .mode("ANY")
                                .allowedFunctionNames(allowedFunctionNames)
                                .build())
                        .build());
            } else if (this.toolConfig != null) {
                // Use pre-configured ToolConfig
                configBuilder.toolConfig(this.toolConfig);
            } else {
                // Default to AUTO
                configBuilder.toolConfig(ToolConfig.builder()
                        .functionCallingConfig(FunctionCallingConfig.builder().mode("AUTO").build())
                        .build());
            }
        }

        if (!requestTools.isEmpty()) {
            configBuilder.tools(requestTools);
        }

        GenerateContentConfig finalConfig = configBuilder.build();

        // --- 4. Notify Listeners & Log ---
        ChatModelRequest request = new ChatRequest(messages, toolSpecifications);
        ChatModelRequestContext requestContext = new ChatModelRequestContext(request, new ConcurrentHashMap<>());
        listeners.forEach(listener -> {
            try { listener.onRequest(requestContext); } catch (Exception e) { log.warn("Listener onRequest failed", e); }
        });

        if (logRequests) {
            log.info("Google GenAI Request: model={}, tools={}, msgCount={}", modelName,
                    (toolSpecifications != null ? toolSpecifications.size() : 0), messages.size());
        }

        // --- 5. Execute API Call (with Retry) ---
        GenerateContentResponse result = null;
        RuntimeException lastException = null;

        for (int i = 0; i <= maxRetries; i++) {
            try {
                result = client.models().generateContent(modelName, contents, finalConfig);
                break;
            } catch (Exception e) {
                lastException = new RuntimeException("Google GenAI call failed", e);
                log.warn("Attempt {}/{} failed: {}", i + 1, maxRetries + 1, e.getMessage());
                if (i < maxRetries) {
                    try { Thread.sleep((long) (Math.pow(2, i) * 1000)); } catch (InterruptedException ignored) {}
                }
            }
        }

        if (result == null) throw lastException;

        // --- 6. Map Response ---
        Response<AiMessage> response = GoogleGenAiMapper.toResponse(result);

        // --- 7. Notify Listeners & Log ---
        if (logResponses) {
            log.info("Google GenAI Response: tokenUsage={}, finishReason={}", response.tokenUsage(), response.finishReason());
        }

        ChatModelResponseContext responseContext = new ChatModelResponseContext(response, requestContext, new ConcurrentHashMap<>());
        listeners.forEach(listener -> {
            try { listener.onResponse(responseContext); } catch (Exception e) { log.warn("Listener onResponse failed", e); }
        });

        return response;
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return supportedCapabilities;
    }

    // =================================================================================================
    // MAPPER HELPER (Inner class for containment)
    // =================================================================================================
    private static class GoogleGenAiMapper {

        static Content toContent(ChatMessage message) {
            if (message instanceof UserMessage) {
                return Content.builder().role("user")
                        .parts(singletonList(Part.builder().text(((UserMessage) message).text()).build()))
                        .build();
            } else if (message instanceof AiMessage) {
                AiMessage aiMsg = (AiMessage) message;
                List<Part> parts = new ArrayList<>();
                if (aiMsg.hasText()) parts.add(Part.builder().text(aiMsg.text()).build());
                if (aiMsg.hasToolExecutionRequests()) {
                    for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
                        // Assume internal helper or library to convert JSON String -> Map
                        Map<String, Object> args = new HashMap<>(); // Placeholder: Parsing logic needed here
                        parts.add(Part.builder()
                                .functionCall(FunctionCall.builder().name(req.name()).args(args).build())
                                .build());
                    }
                }
                return Content.builder().role("model").parts(parts).build();
            } else if (message instanceof ToolExecutionResultMessage) {
                ToolExecutionResultMessage toolMsg = (ToolExecutionResultMessage) message;
                Map<String, Object> responseMap = new HashMap<>();
                responseMap.put("result", toolMsg.text()); // Wrap result for Google
                return Content.builder().role("function") // Some API versions use 'user' or 'function'
                        .parts(singletonList(Part.builder()
                                .functionResponse(FunctionResponse.builder()
                                        .name(toolMsg.toolName())
                                        .response(responseMap)
                                        .build())
                                .build()))
                        .build();
            }
            throw new IllegalArgumentException("Unknown message type: " + message.type());
        }

        static FunctionDeclaration toFunctionDeclaration(ToolSpecification tool) {
            // Simplified mapping. Real implementation requires converting JsonSchemaProperty to Google Schema
            return FunctionDeclaration.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .build();
        }

        static Response<AiMessage> toResponse(GenerateContentResponse response) {
            Candidate candidate = response.candidates().get(0);
            Content content = candidate.content();

            String text = "";
            List<ToolExecutionRequest> toolRequests = new ArrayList<>();

            if (content.parts() != null) {
                for (Part part : content.parts()) {
                    if (part.text() != null) text += part.text();
                    if (part.functionCall() != null) {
                        toolRequests.add(ToolExecutionRequest.builder()
                                .name(part.functionCall().name())
                                .arguments("{}") // Placeholder: Convert Map back to JSON string
                                .build());
                    }
                }
            }

            // Usage mapping
            TokenUsage usage = null;
            if (response.usageMetadata() != null) {
                usage = new TokenUsage(
                        response.usageMetadata().promptTokenCount(),
                        response.usageMetadata().candidatesTokenCount()
                );
            }

            AiMessage aiMessage = toolRequests.isEmpty() ? AiMessage.from(text) : AiMessage.from(toolRequests);
            return Response.from(aiMessage, usage, FinishReason.STOP);
        }
    }

    // =================================================================================================
    // BUILDER
    // =================================================================================================
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
        private Double temperature;
        private Double topP;
        private Integer topK;
        private Integer maxOutputTokens;
        private List<String> stopSequences;
        private Duration timeout;

        // Features
        private Integer maxRetries = 3;
        private Map<HarmCategory, SafetyThreshold> safetySettings;
        private Boolean googleSearch;
        private ToolConfig toolConfig;
        private List<String> allowedFunctionNames;
        private Boolean logRequests;
        private Boolean logResponses;
        private List<ChatModelListener> listeners;

        public Builder client(Client client) { this.client = client; return this; }
        public Builder googleCredentials(GoogleCredentials credentials) { this.googleCredentials = credentials; return this; }
        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public Builder projectId(String projectId) { this.projectId = projectId; return this; }
        public Builder location(String location) { this.location = location; return this; }
        public Builder modelName(String modelName) { this.modelName = modelName; return this; }
        public Builder timeout(Duration timeout) { this.timeout = timeout; return this; }

        public Builder temperature(Double temperature) { this.temperature = temperature; return this; }
        public Builder topP(Double topP) { this.topP = topP; return this; }
        public Builder topK(Integer topK) { this.topK = topK; return this; }
        public Builder maxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; return this; }
        public Builder stopSequences(List<String> stopSequences) { this.stopSequences = stopSequences; return this; }

        public Builder maxRetries(Integer maxRetries) { this.maxRetries = maxRetries; return this; }
        public Builder safetySettings(Map<HarmCategory, SafetyThreshold> safetySettings) { this.safetySettings = safetySettings; return this; }
        public Builder enableGoogleSearch(boolean googleSearch) { this.googleSearch = googleSearch; return this; }
        public Builder toolConfig(ToolConfig toolConfig) { this.toolConfig = toolConfig; return this; }
        public Builder allowedFunctionNames(List<String> allowedFunctionNames) { this.allowedFunctionNames = allowedFunctionNames; return this; }
        public Builder logRequests(Boolean logRequests) { this.logRequests = logRequests; return this; }
        public Builder logResponses(Boolean logResponses) { this.logResponses = logResponses; return this; }
        public Builder listeners(List<ChatModelListener> listeners) { this.listeners = listeners; return this; }

        public GoogleGenAiChatModel build() {
            return new GoogleGenAiChatModel(this);
        }
    }

    // Placeholder Enums for user convenience (mapping to SDK strings internally)
    public enum HarmCategory { HARM_CATEGORY_HATE_SPEECH, HARM_CATEGORY_DANGEROUS_CONTENT, HARM_CATEGORY_HARASSMENT, HARM_CATEGORY_SEXUALLY_EXPLICIT }
    public enum SafetyThreshold { BLOCK_NONE, BLOCK_ONLY_HIGH, BLOCK_MEDIUM_AND_ABOVE, BLOCK_LOW_AND_ABOVE }
}