package com.eainde.agent;

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
import dev.langchain4j.model.chat.request.ResponseFormat;
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

/**
 * Custom LangChain4j ChatModel for Google Gen AI SDK (0.34+ API).
 */
public class GoogleGenAiChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(GoogleGenAiChatModel.class);

    private final Client client;
    private final String modelName;
    private final Integer maxRetries;
    private final Boolean logRequests;
    private final Boolean logResponses;
    private final List<ChatModelListener> listeners;

    // Default parameters (Temperature, MaxTokens, Tools, etc.)
    private final ChatRequestParameters defaultRequestParameters;

    // Custom Google-specific settings (Not covered by standard ChatRequestParameters)
    private final Map<String, String> safetySettings; // Mapped to Strings for internal storage
    private final boolean googleSearchEnabled;
    private final List<String> allowedFunctionNames; // Global allow-list
    private final ToolConfig toolConfig; // Custom Google ToolConfig (optional)

    private GoogleGenAiChatModel(Builder builder) {
        this.modelName = ensureNotBlank(builder.modelName, "modelName");
        this.maxRetries = builder.maxRetries == null ? 3 : builder.maxRetries;
        this.logRequests = builder.logRequests != null && builder.logRequests;
        this.logResponses = builder.logResponses != null && builder.logResponses;
        this.listeners = builder.listeners == null ? emptyList() : new ArrayList<>(builder.listeners);
        this.googleSearchEnabled = builder.googleSearch != null && builder.googleSearch;
        this.allowedFunctionNames = builder.allowedFunctionNames;
        this.toolConfig = builder.toolConfig;

        // Convert Map<Enum, Enum> to internal Map<String, String> for Safety
        this.safetySettings = new HashMap<>();
        if (builder.safetySettings != null) {
            builder.safetySettings.forEach((k, v) -> this.safetySettings.put(k.toString(), v.toString()));
        }

        // Initialize Client with Auth and Timeout
        if (builder.client != null) {
            this.client = builder.client;
        } else {
            HttpOptions.Builder httpOptions = HttpOptions.builder();
            if (builder.timeout != null) {
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

        // Initialize Default Request Parameters
        this.defaultRequestParameters = DefaultChatRequestParameters.builder()
                .temperature(builder.temperature)
                .maxOutputTokens(builder.maxOutputTokens)
                .topP(builder.topP)
                .topK(builder.topK)
                .stopSequences(builder.stopSequences)
                .build();
    }

    // --- 1. The Main Method: doChat ---
    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        ChatRequestParameters parameters = chatRequest.parameters();
        List<ChatMessage> messages = chatRequest.messages();

        // A. Map Messages to Google Content
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

        // B. Build Configuration (Merge defaults + request params)
        GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder();

        // Standard Parameters
        if (parameters.temperature() != null) configBuilder.temperature(parameters.temperature().floatValue());
        if (parameters.topP() != null) configBuilder.topP(parameters.topP().floatValue());
        if (parameters.topK() != null) configBuilder.topK(parameters.topK());
        if (parameters.maxOutputTokens() != null) configBuilder.maxOutputTokens(parameters.maxOutputTokens());
        if (parameters.stopSequences() != null) configBuilder.stopSequences(parameters.stopSequences());

        // Safety Settings (Always applied from Builder)
        if (!safetySettings.isEmpty()) {
            List<SafetySetting> safetyList = new ArrayList<>();
            safetySettings.forEach((cat, thresh) ->
                    safetyList.add(SafetySetting.builder().category(cat).threshold(thresh).build())
            );
            configBuilder.safetySettings(safetyList);
        }

        // System Instruction
        if (systemInstruction.length() > 0) {
            configBuilder.systemInstruction(Content.builder()
                    .parts(singletonList(Part.builder().text(systemInstruction.toString()).build()))
                    .build());
        }

        // JSON Response Mode
        if (parameters.responseFormat() != null && parameters.responseFormat().type() == ResponseFormatType.JSON) {
            configBuilder.responseMimeType("application/json");
        }

        // C. Tools (Function Calling & Grounding)
        List<Tool> requestTools = new ArrayList<>();

        // 1. Google Search
        if (this.googleSearchEnabled) {
            requestTools.add(Tool.builder().googleSearch(GoogleSearch.builder().build()).build());
        }

        // 2. Function Calling
        List<ToolSpecification> toolSpecs = parameters.toolSpecifications();
        if (!isNullOrEmpty(toolSpecs)) {
            List<FunctionDeclaration> decls = toolSpecs.stream()
                    .map(GoogleGenAiMapper::toFunctionDeclaration)
                    .collect(Collectors.toList());

            requestTools.add(Tool.builder().functionDeclarations(decls).build());

            // Handle Tool Choice (AUTO / REQUIRED / NONE)
            FunctionCallingConfig.Builder funcConfig = FunctionCallingConfig.builder();
            ToolChoice toolChoice = parameters.toolChoice();

            if (toolChoice == ToolChoice.REQUIRED) {
                funcConfig.mode("ANY");
            } else if (toolChoice instanceof ToolChoice.ForcedToolChoice) {
                funcConfig.mode("ANY");
                funcConfig.allowedFunctionNames(singletonList(((ToolChoice.ForcedToolChoice) toolChoice).name()));
            } else {
                funcConfig.mode("AUTO");
            }

            // Override with global allow-list if set
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

        // D. Execute
        if (logRequests) log.info("Google Request: model={}, msgCount={}", modelName, messages.size());

        GenerateContentResponse result = null;
        RuntimeException lastException = null;

        for (int i = 0; i <= maxRetries; i++) {
            try {
                result = client.models().generateContent(modelName, contents, configBuilder.build());
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

    // --- 2. Interface Methods ---

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
        return ModelProvider.GOOGLE_VERTEX_AI; // Or create a custom ModelProvider enum if needed
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return Set.of(Capability.RESPONSE_FORMAT_JSON, Capability.TOOL_EXECUTION);
    }

    // --- 3. Mapper Helper ---

    private static class GoogleGenAiMapper {
        static Content toContent(ChatMessage message) {
            // ... (Same content mapping logic as previous response) ...
            // Simplified for brevity in this snippet
            if (message instanceof UserMessage) {
                return Content.builder().role("user").parts(singletonList(Part.builder().text(((UserMessage) message).text()).build())).build();
            }
            // Add full mapping for AI, System, ToolResult...
            return Content.builder().role("user").parts(singletonList(Part.builder().text(message.text()).build())).build();
        }

        static FunctionDeclaration toFunctionDeclaration(ToolSpecification tool) {
            return FunctionDeclaration.builder().name(tool.name()).description(tool.description()).build();
        }

        static ChatResponse toChatResponse(GenerateContentResponse response) {
            Candidate candidate = response.candidates().get(0);

            // Extract text and tool calls
            String text = "";
            List<ToolExecutionRequest> toolRequests = new ArrayList<>();

            if (candidate.content() != null && candidate.content().parts() != null) {
                for (Part part : candidate.content().parts()) {
                    if (part.text() != null) text += part.text();
                    if (part.functionCall() != null) {
                        toolRequests.add(ToolExecutionRequest.builder()
                                .name(part.functionCall().name())
                                .arguments(part.functionCall().args().toString()) // Basic toString, prefer JSON serializer
                                .build());
                    }
                }
            }

            AiMessage aiMessage = toolRequests.isEmpty() ? AiMessage.from(text) : AiMessage.from(toolRequests);

            TokenUsage usage = (response.usageMetadata() != null)
                    ? new TokenUsage(response.usageMetadata().promptTokenCount(), response.usageMetadata().candidatesTokenCount())
                    : new TokenUsage(0, 0);

            return ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .tokenUsage(usage)
                    .finishReason(FinishReason.STOP)
                    .build();
        }
    }

    // --- 4. Builder ---

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        // ... (Same Builder fields as previous response) ...
        // Ensure you have fields for all defaults:
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

        // ... Setters ...

        public GoogleGenAiChatModel build() {
            return new GoogleGenAiChatModel(this);
        }
    }

    public enum HarmCategory { HARM_CATEGORY_HATE_SPEECH, HARM_CATEGORY_DANGEROUS_CONTENT, HARM_CATEGORY_HARASSMENT, HARM_CATEGORY_SEXUALLY_EXPLICIT }
    public enum SafetyThreshold { BLOCK_NONE, BLOCK_ONLY_HIGH, BLOCK_MEDIUM_AND_ABOVE, BLOCK_LOW_AND_ABOVE }
}