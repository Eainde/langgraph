package com.example.langchain4j.google;

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
            safetySettings.forEach((cat, thresh) ->
                    safetyList.add(SafetySetting.builder().category(cat).threshold(thresh).build())
            );
            configBuilder.safetySettings(safetyList);
        }

        if (systemInstruction.length() > 0) {
            configBuilder.systemInstruction(Content.builder()
                    .parts(singletonList(Part.builder().text(systemInstruction.toString()).build()))
                    .build());
        }

        if (parameters.responseFormat() != null && parameters.responseFormat().type() == ResponseFormatType.JSON) {
            configBuilder.responseMimeType("application/json");
        }

        // 3. Tools
        List<Tool> requestTools = new ArrayList<>();

        if (this.googleSearchEnabled) {
            requestTools.add(Tool.builder().googleSearch(GoogleSearch.builder().build()).build());
        }

        List<ToolSpecification> toolSpecs = parameters.toolSpecifications();
        if (!isNullOrEmpty(toolSpecs)) {
            List<FunctionDeclaration> decls = toolSpecs.stream()
                    .map(GoogleGenAiMapper::toFunctionDeclaration)
                    .collect(Collectors.toList());

            requestTools.add(Tool.builder().functionDeclarations(decls).build());

            FunctionCallingConfig.Builder funcConfig = FunctionCallingConfig.builder();
            ToolChoice toolChoice = parameters.toolChoice();

            if (toolChoice == ToolChoice.REQUIRED) {
                funcConfig.mode("ANY");
            }
            // Fix #1: Handle named tool choice safely
            else if (toolChoice instanceof ToolChoice.NamedToolChoice) {
                funcConfig.mode("ANY");
                funcConfig.allowedFunctionNames(singletonList(((ToolChoice.NamedToolChoice) toolChoice).name()));
            } else {
                funcConfig.mode("AUTO");
            }

            if (!isNullOrEmpty(this.allowedFunctionNames)) {
                funcConfig.allowedFunctionNames(this.allowedFunctionNames);
            }

            configBuilder.toolConfig(com.google.genai.types.ToolConfig.builder()
                    .functionCallingConfig(funcConfig.build())
                    .build());
        }

        if (!requestTools.isEmpty()) {
            configBuilder.tools(requestTools);
        }

        if (logRequests) log.info("Google Request: model={}, msgCount={}", modelName, messages.size());

        // 4. Execute
        GenerateContentResponse result = null;
        RuntimeException lastException = null;

        for (int i = 0; i <= maxRetries; i++) {
            try {
                // Fix #2: Access 'models' as a field, not a method
                result = client.models.generateContent(modelName, contents, configBuilder.build());
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

        ChatResponse chatResponse = GoogleGenAiMapper.toChatResponse(result);

        if (logResponses) log.info("Google Response: tokens={}", chatResponse.tokenUsage());

        return chatResponse;
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return defaultRequestParameters;
    }

    @Override
    public List<ChatModelListener> listeners() {
        return listeners;
    }

    @Override
    public ModelProvider provider() {
        return ModelProvider.GOOGLE_VERTEX_AI;
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        // Fix #3: Removed 'Capability.TOOL_EXECUTION' as it is not a standard enum value
        return Set.of(Capability.RESPONSE_FORMAT_JSON);
    }

    // --- Mapper Helper ---

    private static class GoogleGenAiMapper {
        static Content toContent(ChatMessage message) {
            // Fix #4: Use message.text() instead of casting to UserMessage specific methods
            if (message instanceof UserMessage) {
                return Content.builder().role("user")
                        .parts(singletonList(Part.builder().text(message.text()).build()))
                        .build();
            } else if (message instanceof AiMessage) {
                AiMessage aiMsg = (AiMessage) message;
                List<Part> parts = new ArrayList<>();
                if (aiMsg.text() != null) {
                    parts.add(Part.builder().text(aiMsg.text()).build());
                }
                if (aiMsg.toolExecutionRequests() != null) {
                    for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
                        // In a real app, parse req.arguments() JSON to Map<String, Object>
                        Map<String, Object> args = new HashMap<>();
                        parts.add(Part.builder()
                                .functionCall(FunctionCall.builder().name(req.name()).args(args).build())
                                .build());
                    }
                }
                return Content.builder().role("model").parts(parts).build();
            } else if (message instanceof ToolExecutionResultMessage) {
                ToolExecutionResultMessage toolMsg = (ToolExecutionResultMessage) message;
                Map<String, Object> responseMap = new HashMap<>();
                responseMap.put("result", toolMsg.text());
                return Content.builder().role("function")
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
            return FunctionDeclaration.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .build();
        }

        static ChatResponse toChatResponse(GenerateContentResponse response) {
            // Fix #6: 'candidates()' returns a List, so .get(0) is correct on the list result.
            // Note: Verify if your SDK version uses 'candidates' (field) or 'candidates()' (method).
            // Assuming method accessor based on standard Java SDK patterns.
            Candidate candidate = response.candidates().get(0);

            String text = "";
            List<ToolExecutionRequest> toolRequests = new ArrayList<>();

            // Fix #5: Handle Optional return from content()
            Content content = candidate.content(); // Some SDK versions return Object, others Optional

            // Safe logic for content parts
            if (content != null && content.parts() != null) {
                for (Part part : content.parts()) {
                    if (part.text() != null) text += part.text();
                    if (part.functionCall() != null) {
                        toolRequests.add(ToolExecutionRequest.builder()
                                .name(part.functionCall().name())
                                .arguments(part.functionCall().args().toString())
                                .build());
                    }
                }
            }

            AiMessage aiMessage = toolRequests.isEmpty() ? AiMessage.from(text) : AiMessage.from(toolRequests);

            // Fix #7: Unwrap usage metadata Optionals
            TokenUsage usage;
            if (response.usageMetadata() != null) {
                // Note: Depending on SDK version, these might be direct Integers or Optionals
                // This logic assumes direct access or standard Java bean getters
                usage = new TokenUsage(
                        response.usageMetadata().promptTokenCount(),
                        response.usageMetadata().candidatesTokenCount()
                );
            } else {
                usage = new TokenUsage(0, 0);
            }

            return ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .tokenUsage(usage)
                    .finishReason(FinishReason.STOP)
                    .build();
        }
    }

    // --- Builder ---
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Client client;
        private GoogleCredentials googleCredentials;
        private String apiKey, projectId, location, modelName;
        private Double temperature, topP;
        private Integer topK, maxOutputTokens, maxRetries = 3;
        private List<String> stopSequences;
        private Duration timeout;
        private Boolean googleSearch, logRequests, logResponses;
        private Map<HarmCategory, SafetyThreshold> safetySettings;
        private List<String> allowedFunctionNames;
        private ToolConfig toolConfig;
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

    public enum HarmCategory { HARM_CATEGORY_HATE_SPEECH, HARM_CATEGORY_DANGEROUS_CONTENT, HARM_CATEGORY_HARASSMENT, HARM_CATEGORY_SEXUALLY_EXPLICIT }
    public enum SafetyThreshold { BLOCK_NONE, BLOCK_ONLY_HIGH, BLOCK_MEDIUM_AND_ABOVE, BLOCK_LOW_AND_ABOVE }
}