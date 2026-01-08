package com.eainde.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.types.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dev.langchain4j.internal.Utils.isNullOrEmpty;
import static dev.langchain4j.internal.ValidationUtils.ensureNotBlank;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

/**
 * Streaming ChatModel implementation for Google Gen AI SDK (com.google.genai).
 * Handles real-time token streaming, tool execution accumulation, and error handling.
 */
public class GoogleGenAiStreamingChatModel implements StreamingChatModel {

    private static final Logger log = LoggerFactory.getLogger(GoogleGenAiStreamingChatModel.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Client client;
    private final String modelName;
    private final Boolean logRequests;
    private final Boolean logResponses;
    private final List<ChatModelListener> listeners;
    private final ChatRequestParameters defaultRequestParameters;

    // Configurable Fields
    private final List<SafetySetting> safetySettings;
    private final Schema responseSchema;
    private final String responseMimeType;
    private final boolean googleSearchEnabled;
    private final List<String> allowedFunctionNames;
    private final ToolConfig toolConfig;

    private GoogleGenAiStreamingChatModel(Builder builder) {
        this.modelName = ensureNotBlank(builder.modelName, "modelName");
        this.logRequests = builder.logRequests != null && builder.logRequests;
        this.logResponses = builder.logResponses != null && builder.logResponses;
        this.listeners = builder.listeners == null ? emptyList() : new ArrayList<>(builder.listeners);
        this.googleSearchEnabled = builder.googleSearch != null && builder.googleSearch;
        this.allowedFunctionNames = builder.allowedFunctionNames;
        this.toolConfig = builder.toolConfig;
        this.responseSchema = builder.responseSchema;
        this.responseMimeType = builder.responseMimeType;

        this.safetySettings = builder.safetySettings != null ? new ArrayList<>(builder.safetySettings) : new ArrayList<>();

        if (builder.client != null) {
            this.client = builder.client;
        } else {
            HttpOptions.Builder httpOptions = HttpOptions.builder();
            if (builder.timeout != null) {
                // Cast to int for SDK compatibility
                httpOptions.timeout((int) builder.timeout.toMillis());
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

    // --- 1. Main Streaming Method ---

    @Override
    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        ChatRequestParameters parameters = chatRequest.parameters();
        List<ChatMessage> messages = chatRequest.messages();

        // Map Input (Same logic as synchronous model)
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

        // Build Config
        GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder();
        if (parameters.temperature() != null) configBuilder.temperature(parameters.temperature().floatValue());
        if (parameters.topP() != null) configBuilder.topP(parameters.topP().floatValue());
        if (parameters.topK() != null) configBuilder.topK(parameters.topK());
        if (parameters.maxOutputTokens() != null) configBuilder.maxOutputTokens(parameters.maxOutputTokens());
        if (parameters.stopSequences() != null) configBuilder.stopSequences(parameters.stopSequences());

        // Apply advanced settings
        if (!safetySettings.isEmpty()) configBuilder.safetySettings(safetySettings);
        if (responseMimeType != null) configBuilder.responseMimeType(responseMimeType);
        if (responseSchema != null) {
            configBuilder.responseSchema(responseSchema);
            configBuilder.responseMimeType("application/json");
        }
        if (parameters.responseFormat() != null && parameters.responseFormat().type() == ResponseFormatType.JSON) {
            configBuilder.responseMimeType("application/json");
        }

        if (systemInstruction.length() > 0) {
            configBuilder.systemInstruction(Content.builder()
                    .parts(singletonList(Part.builder().text(systemInstruction.toString()).build()))
                    .build());
        }

        // Tools
        List<Tool> requestTools = new ArrayList<>();
        if (googleSearchEnabled) {
            requestTools.add(Tool.builder().googleSearch(GoogleSearch.builder().build()).build());
        }
        List<ToolSpecification> toolSpecs = parameters.toolSpecifications();
        if (!isNullOrEmpty(toolSpecs)) {
            List<FunctionDeclaration> decls = toolSpecs.stream()
                    .map(GoogleGenAiMapper::toFunctionDeclaration)
                    .collect(Collectors.toList());
            requestTools.add(Tool.builder().functionDeclarations(decls).build());

            FunctionCallingConfig.Builder funcConfig = FunctionCallingConfig.builder();
            // Handle ToolChoice
            if (parameters.toolChoice() == ToolChoice.REQUIRED) funcConfig.mode("ANY");
            else if (parameters.toolChoice() == ToolChoice.NONE) funcConfig.mode("NONE");
            else funcConfig.mode("AUTO");

            if (!isNullOrEmpty(allowedFunctionNames)) funcConfig.allowedFunctionNames(allowedFunctionNames);
            configBuilder.toolConfig(com.google.genai.types.ToolConfig.builder().functionCallingConfig(funcConfig.build()).build());
        }
        if (!requestTools.isEmpty()) configBuilder.tools(requestTools);

        if (logRequests) log.info("Google Stream Request: model={}", modelName);

        // --- Execute Streaming ---
        try {
            // Note: Use field 'models' access
            Stream<GenerateContentResponse> responseStream = client.models.streamGenerateContent(modelName, contents, configBuilder.build());

            StreamHandler internalHandler = new StreamHandler(handler);

            // Iterate stream and process each chunk
            responseStream.forEach(internalHandler::processChunk);

            internalHandler.onComplete();

        } catch (Exception e) {
            log.error("Streaming failed", e);
            handler.onError(e);
        }
    }

    // --- 2. Stream Processor Helper ---

    private class StreamHandler {
        private final StreamingChatResponseHandler handler;
        private final StringBuilder fullTextBuilder = new StringBuilder();
        private final List<ToolExecutionRequest> toolRequests = new ArrayList<>();
        private TokenUsage tokenUsage;
        private FinishReason finishReason;

        StreamHandler(StreamingChatResponseHandler handler) {
            this.handler = handler;
        }

        void processChunk(GenerateContentResponse chunk) {
            // 1. Extract Token Usage (if available in this chunk)
            if (chunk.usageMetadata() != null && chunk.usageMetadata().isPresent()) {
                GenerateContentResponseUsageMetadata meta = chunk.usageMetadata().get();
                this.tokenUsage = new TokenUsage(
                        meta.promptTokenCount() != null ? meta.promptTokenCount() : 0,
                        meta.candidatesTokenCount() != null ? meta.candidatesTokenCount() : 0
                );
            }

            // 2. Extract Candidates
            List<Candidate> candidates = chunk.candidates().orElse(Collections.emptyList());
            if (candidates.isEmpty()) return;
            Candidate candidate = candidates.get(0);

            // 3. Extract Content
            Content content = candidate.content().orElse(null);
            if (content != null) {
                List<Part> parts = content.parts().orElse(Collections.emptyList());
                for (Part part : parts) {
                    // A. Text Streaming
                    if (part.text() != null) {
                        String partialText = part.text();
                        fullTextBuilder.append(partialText);
                        // Notify Listener
                        handler.onPartialResponse(partialText);
                    }

                    // B. Tool Call Accumulation (Usually tools arrive in one chunk, but we accumulate just in case)
                    if (part.functionCall().isPresent()) {
                        FunctionCall fc = part.functionCall().get();
                        String name = fc.name().orElse("unknown");
                        String args = fc.args().map(Object::toString).orElse("{}");
                        toolRequests.add(ToolExecutionRequest.builder().name(name).arguments(args).build());
                    }
                }
            }

            // 4. Check Finish Reason
            if (candidate.finishReason() != null) {
                this.finishReason = mapFinishReason(candidate.finishReason());
            }
        }

        void onComplete() {
            String fullText = fullTextBuilder.toString();
            AiMessage aiMessage;

            // Construct final AiMessage (Handling Mixed Content)
            if (!toolRequests.isEmpty()) {
                if (fullText != null && !fullText.isBlank()) {
                    aiMessage = new AiMessage(fullText, toolRequests);
                } else {
                    aiMessage = AiMessage.from(toolRequests);
                }
            } else {
                aiMessage = AiMessage.from(fullText);
            }

            ChatResponse response = ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .tokenUsage(tokenUsage != null ? tokenUsage : new TokenUsage(0, 0))
                    .finishReason(finishReason != null ? finishReason : FinishReason.STOP)
                    .build();

            if (logResponses) log.info("Stream Complete: {}", response);

            handler.onCompleteResponse(response);
        }

        private FinishReason mapFinishReason(String googleReason) {
            switch (googleReason) {
                case "STOP": return FinishReason.STOP;
                case "MAX_TOKENS": return FinishReason.LENGTH;
                case "SAFETY": return FinishReason.CONTENT_FILTER;
                case "RECITATION": return FinishReason.CONTENT_FILTER;
                default: return FinishReason.OTHER;
            }
        }
    }

    // --- 3. Mappers ---

    private static class GoogleGenAiMapper {
        static Content toContent(ChatMessage message) {
            if (message instanceof UserMessage) {
                return Content.builder().role("user").parts(singletonList(Part.builder().text(message.text()).build())).build();
            } else if (message instanceof AiMessage) {
                AiMessage aiMsg = (AiMessage) message;
                List<Part> parts = new ArrayList<>();
                if (aiMsg.text() != null) parts.add(Part.builder().text(aiMsg.text()).build());
                if (aiMsg.toolExecutionRequests() != null) {
                    for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
                        // Safe JSON Parsing Logic
                        Map<String, Object> args = new HashMap<>();
                        if (req.arguments() != null && !req.arguments().isEmpty()) {
                            try { args = OBJECT_MAPPER.readValue(req.arguments(), new TypeReference<Map<String, Object>>(){}); }
                            catch (Exception ignored) {}
                        }
                        parts.add(Part.builder().functionCall(FunctionCall.builder().name(req.name()).args(args).build()).build());
                    }
                }
                return Content.builder().role("model").parts(parts).build();
            } else if (message instanceof ToolExecutionResultMessage) {
                ToolExecutionResultMessage toolMsg = (ToolExecutionResultMessage) message;
                Map<String, Object> resMap = new HashMap<>();
                resMap.put("result", toolMsg.text());
                return Content.builder().role("function").parts(singletonList(Part.builder()
                        .functionResponse(FunctionResponse.builder().name(toolMsg.toolName()).response(resMap).build()).build())).build();
            }
            throw new IllegalArgumentException("Unknown type: " + message.type());
        }

        static FunctionDeclaration toFunctionDeclaration(ToolSpecification tool) {
            return FunctionDeclaration.builder().name(tool.name()).description(tool.description()).build();
        }
    }

    // --- 4. Builder ---

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Client client;
        private GoogleCredentials googleCredentials;
        private String apiKey, projectId, location, modelName;
        private Double temperature, topP;
        private Integer topK, maxOutputTokens;
        private List<String> stopSequences;
        private Duration timeout;
        private Boolean googleSearch, logRequests, logResponses;
        private List<SafetySetting> safetySettings;
        private Schema responseSchema;
        private String responseMimeType;
        private List<String> allowedFunctionNames;
        private ToolConfig toolConfig;
        private List<ChatModelListener> listeners;

        public Builder client(Client client) { this.client = client; return this; }
        public Builder googleCredentials(GoogleCredentials c) { this.googleCredentials = c; return this; }
        public Builder apiKey(String k) { this.apiKey = k; return this; }
        public Builder projectId(String p) { this.projectId = p; return this; }
        public Builder location(String l) { this.location = l; return this; }
        public Builder modelName(String m) { this.modelName = m; return this; }
        public Builder timeout(Duration t) { this.timeout = t; return this; }
        public Builder temperature(Double t) { this.temperature = t; return this; }
        public Builder topP(Double p) { this.topP = p; return this; }
        public Builder topK(Integer k) { this.topK = k; return this; }
        public Builder maxOutputTokens(Integer m) { this.maxOutputTokens = m; return this; }
        public Builder stopSequences(List<String> s) { this.stopSequences = s; return this; }
        public Builder safetySettings(List<SafetySetting> s) { this.safetySettings = s; return this; }
        public Builder responseSchema(Schema s) { this.responseSchema = s; return this; }
        public Builder responseMimeType(String s) { this.responseMimeType = s; return this; }
        public Builder enableGoogleSearch(boolean b) { this.googleSearch = b; return this; }
        public Builder toolConfig(ToolConfig t) { this.toolConfig = t; return this; }
        public Builder allowedFunctionNames(List<String> l) { this.allowedFunctionNames = l; return this; }
        public Builder logRequests(Boolean b) { this.logRequests = b; return this; }
        public Builder logResponses(Boolean b) { this.logResponses = b; return this; }
        public Builder listeners(List<ChatModelListener> l) { this.listeners = l; return this; }

        public GoogleGenAiStreamingChatModel build() {
            return new GoogleGenAiStreamingChatModel(this);
        }
    }

    // --- 5. Interface methods ---
    @Override
    public ChatRequestParameters defaultRequestParameters() { return defaultRequestParameters; }
    @Override
    public List<ChatModelListener> listeners() { return listeners; }
}
