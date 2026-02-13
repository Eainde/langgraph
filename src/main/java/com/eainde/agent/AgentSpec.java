package com.eainde.agent;

package com.db.clm.kyc.ai.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.output.ToolExecutionResultMessage;
import dev.langchain4j.agentic.AgentListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Comprehensive declarative specification for an agent.
 * Maps 1:1 to all capabilities of LangChain4j's AgentBuilder API.
 *
 * Prompts (system/user) are resolved from DB by {@link AgentFactory} using agentName.
 * All other settings are optional — only set what each agent actually needs.
 *
 * <pre>
 * // Minimal — just prompts from DB
 * AgentSpec.of("entity-extractor", "Extracts CSM candidates")
 *          .inputs("sourceText")
 *          .outputKey("candidates")
 *          .build();
 *
 * // Full-featured — tools, RAG, guardrails, memory, etc.
 * AgentSpec.of("research-agent", "Researches and validates candidates")
 *          .inputs("candidates")
 *          .outputKey("researchResults")
 *          .tools(webSearchTool, dbLookupTool)
 *          .toolProvider(mcpToolProvider)
 *          .contentRetriever(embeddingStoreRetriever)
 *          .chatMemoryProvider(memId -> MessageWindowChatMemory.withMaxMessages(20))
 *          .inputGuardrails(piiGuardrail, promptInjectionGuardrail)
 *          .outputGuardrails(toxicityGuardrail)
 *          .hallucinatedToolNameStrategy(req -> ToolExecutionResultMessage.from(req, "Tool not found"))
 *          .maxSequentialToolExecutions(10)
 *          .listener(monitoringListener)
 *          .async(true)
 *          .build();
 * </pre>
 */
public class AgentSpec {

    // === Core identity ===
    private final String agentName;
    private final String description;
    private final List<String> inputKeys;
    private final String outputKey;

    // === Model override (optional — defaults to shared ChatModel bean) ===
    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;

    // === Tools ===
    private final List<Object> tools;
    private final Map<Object, String> toolsWithDescriptions;
    private final ToolProvider toolProvider;

    // === Memory ===
    private final ChatMemory chatMemory;
    private final ChatMemoryProvider chatMemoryProvider;
    private final List<String> summarizedContextAgents;

    // === RAG ===
    private final ContentRetriever contentRetriever;
    private final RetrievalAugmentor retrievalAugmentor;

    // === Guardrails ===
    private final List<Object> inputGuardrails;
    private final List<Object> outputGuardrails;

    // === Tool execution behavior ===
    private final Function<ToolExecutionRequest, ToolExecutionResultMessage> hallucinatedToolNameStrategy;
    private final Integer maxSequentialToolExecutions;

    // === Request transformation ===
    private final Function<ChatRequest, ChatRequest> chatRequestTransformer;

    // === Observability & execution ===
    private final AgentListener listener;
    private final Boolean async;

    private AgentSpec(Builder builder) {
        this.agentName = builder.agentName;
        this.description = builder.description;
        this.inputKeys = List.copyOf(builder.inputKeys);
        this.outputKey = builder.outputKey;
        this.chatModel = builder.chatModel;
        this.streamingChatModel = builder.streamingChatModel;
        this.tools = List.copyOf(builder.tools);
        this.toolsWithDescriptions = builder.toolsWithDescriptions != null
                ? Map.copyOf(builder.toolsWithDescriptions) : null;
        this.toolProvider = builder.toolProvider;
        this.chatMemory = builder.chatMemory;
        this.chatMemoryProvider = builder.chatMemoryProvider;
        this.summarizedContextAgents = builder.summarizedContextAgents != null
                ? List.copyOf(builder.summarizedContextAgents) : null;
        this.contentRetriever = builder.contentRetriever;
        this.retrievalAugmentor = builder.retrievalAugmentor;
        this.inputGuardrails = List.copyOf(builder.inputGuardrails);
        this.outputGuardrails = List.copyOf(builder.outputGuardrails);
        this.hallucinatedToolNameStrategy = builder.hallucinatedToolNameStrategy;
        this.maxSequentialToolExecutions = builder.maxSequentialToolExecutions;
        this.chatRequestTransformer = builder.chatRequestTransformer;
        this.listener = builder.listener;
        this.async = builder.async;
    }

    // === Factory ===

    public static Builder of(String agentName, String description) {
        return new Builder(agentName, description);
    }

    // === Getters ===

    public String getAgentName() { return agentName; }
    public String getDescription() { return description; }
    public List<String> getInputKeys() { return inputKeys; }
    public String getOutputKey() { return outputKey; }

    public ChatModel getChatModel() { return chatModel; }
    public StreamingChatModel getStreamingChatModel() { return streamingChatModel; }
    public boolean hasModelOverride() { return chatModel != null; }
    public boolean hasStreamingModel() { return streamingChatModel != null; }

    public List<Object> getTools() { return tools; }
    public Map<Object, String> getToolsWithDescriptions() { return toolsWithDescriptions; }
    public ToolProvider getToolProvider() { return toolProvider; }
    public boolean hasTools() { return !tools.isEmpty(); }
    public boolean hasToolsWithDescriptions() { return toolsWithDescriptions != null && !toolsWithDescriptions.isEmpty(); }
    public boolean hasToolProvider() { return toolProvider != null; }

    public ChatMemory getChatMemory() { return chatMemory; }
    public ChatMemoryProvider getChatMemoryProvider() { return chatMemoryProvider; }
    public List<String> getSummarizedContextAgents() { return summarizedContextAgents; }
    public boolean hasChatMemory() { return chatMemory != null; }
    public boolean hasChatMemoryProvider() { return chatMemoryProvider != null; }
    public boolean hasSummarizedContext() { return summarizedContextAgents != null; }

    public ContentRetriever getContentRetriever() { return contentRetriever; }
    public RetrievalAugmentor getRetrievalAugmentor() { return retrievalAugmentor; }
    public boolean hasContentRetriever() { return contentRetriever != null; }
    public boolean hasRetrievalAugmentor() { return retrievalAugmentor != null; }

    public List<Object> getInputGuardrails() { return inputGuardrails; }
    public List<Object> getOutputGuardrails() { return outputGuardrails; }
    public boolean hasInputGuardrails() { return !inputGuardrails.isEmpty(); }
    public boolean hasOutputGuardrails() { return !outputGuardrails.isEmpty(); }

    public Function<ToolExecutionRequest, ToolExecutionResultMessage> getHallucinatedToolNameStrategy() {
        return hallucinatedToolNameStrategy;
    }
    public Integer getMaxSequentialToolExecutions() { return maxSequentialToolExecutions; }
    public boolean hasHallucinatedToolNameStrategy() { return hallucinatedToolNameStrategy != null; }
    public boolean hasMaxSequentialToolExecutions() { return maxSequentialToolExecutions != null; }

    public Function<ChatRequest, ChatRequest> getChatRequestTransformer() { return chatRequestTransformer; }
    public boolean hasChatRequestTransformer() { return chatRequestTransformer != null; }

    public AgentListener getListener() { return listener; }
    public Boolean getAsync() { return async; }
    public boolean hasListener() { return listener != null; }
    public boolean isAsync() { return async != null && async; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(agentName);
        sb.append(" [").append(String.join(",", inputKeys)).append("] → ").append(outputKey);
        if (hasTools()) sb.append(" tools=").append(tools.size());
        if (hasToolProvider()) sb.append(" +toolProvider");
        if (hasChatMemoryProvider() || hasChatMemory()) sb.append(" +memory");
        if (hasContentRetriever() || hasRetrievalAugmentor()) sb.append(" +rag");
        if (hasInputGuardrails()) sb.append(" +inputGuardrails=").append(inputGuardrails.size());
        if (hasOutputGuardrails()) sb.append(" +outputGuardrails=").append(outputGuardrails.size());
        if (isAsync()) sb.append(" [async]");
        return sb.toString();
    }

    // ==========================================================================
    //  Builder
    // ==========================================================================

    public static class Builder {
        private final String agentName;
        private final String description;
        private List<String> inputKeys = List.of();
        private String outputKey;

        private ChatModel chatModel;
        private StreamingChatModel streamingChatModel;

        private final List<Object> tools = new ArrayList<>();
        private Map<Object, String> toolsWithDescriptions;
        private ToolProvider toolProvider;

        private ChatMemory chatMemory;
        private ChatMemoryProvider chatMemoryProvider;
        private List<String> summarizedContextAgents;

        private ContentRetriever contentRetriever;
        private RetrievalAugmentor retrievalAugmentor;

        private final List<Object> inputGuardrails = new ArrayList<>();
        private final List<Object> outputGuardrails = new ArrayList<>();

        private Function<ToolExecutionRequest, ToolExecutionResultMessage> hallucinatedToolNameStrategy;
        private Integer maxSequentialToolExecutions;

        private Function<ChatRequest, ChatRequest> chatRequestTransformer;

        private AgentListener listener;
        private Boolean async;

        private Builder(String agentName, String description) {
            this.agentName = agentName;
            this.description = description;
        }

        // --- Core ---

        /** AgenticScope variable(s) this agent reads. */
        public Builder inputs(String... keys) {
            this.inputKeys = List.of(keys);
            return this;
        }

        /** AgenticScope variable this agent writes its result to. */
        public Builder outputKey(String outputKey) {
            this.outputKey = outputKey;
            return this;
        }

        // --- Model override ---

        /** Override the default ChatModel for this specific agent. */
        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        /** Use a streaming model for this agent. */
        public Builder streamingChatModel(StreamingChatModel streamingChatModel) {
            this.streamingChatModel = streamingChatModel;
            return this;
        }

        // --- Tools ---

        /** Add @Tool-annotated bean instances this agent can invoke via function calling. */
        public Builder tools(Object... toolInstances) {
            Collections.addAll(this.tools, toolInstances);
            return this;
        }

        /** Add tools with explicit descriptions (overrides @Tool description). */
        public Builder toolsWithDescriptions(Map<Object, String> toolsWithDescriptions) {
            this.toolsWithDescriptions = toolsWithDescriptions;
            return this;
        }

        /** Set a ToolProvider for dynamic tool resolution at runtime. */
        public Builder toolProvider(ToolProvider toolProvider) {
            this.toolProvider = toolProvider;
            return this;
        }

        // --- Memory ---

        /** Set a shared ChatMemory instance (single-user / stateless workflows). */
        public Builder chatMemory(ChatMemory chatMemory) {
            this.chatMemory = chatMemory;
            return this;
        }

        /**
         * Set a ChatMemoryProvider for per-user/per-session memory.
         * Use with @MemoryId on the agent interface method parameter.
         */
        public Builder chatMemoryProvider(ChatMemoryProvider chatMemoryProvider) {
            this.chatMemoryProvider = chatMemoryProvider;
            return this;
        }

        /**
         * Enable context summarization from other agents' outputs.
         * Summarizes the specified agents' context before passing to this agent.
         * Reduces token usage in multi-agent workflows with large intermediate outputs.
         *
         * @param agentNames names of agents whose context to summarize (empty = all)
         */
        public Builder summarizedContext(String... agentNames) {
            this.summarizedContextAgents = List.of(agentNames);
            return this;
        }

        // --- RAG ---

        /** Simple RAG: set a ContentRetriever for embedding-store-based retrieval. */
        public Builder contentRetriever(ContentRetriever contentRetriever) {
            this.contentRetriever = contentRetriever;
            return this;
        }

        /** Advanced RAG: set a full RetrievalAugmentor (query routing, reranking, etc). */
        public Builder retrievalAugmentor(RetrievalAugmentor retrievalAugmentor) {
            this.retrievalAugmentor = retrievalAugmentor;
            return this;
        }

        // --- Guardrails ---

        /**
         * Input guardrails — validate/filter user input BEFORE it reaches the LLM.
         * Use for: PII detection, prompt injection prevention, topic filtering.
         * Instances must implement InputGuardrail interface.
         */
        public Builder inputGuardrails(Object... guardrails) {
            Collections.addAll(this.inputGuardrails, guardrails);
            return this;
        }

        /**
         * Output guardrails — validate/filter LLM output BEFORE returning to caller.
         * Use for: toxicity filtering, hallucination detection, format validation.
         * Instances must implement OutputGuardrail interface.
         * Can trigger automatic LLM retry if validation fails.
         */
        public Builder outputGuardrails(Object... guardrails) {
            Collections.addAll(this.outputGuardrails, guardrails);
            return this;
        }

        // --- Tool execution behavior ---

        /**
         * Strategy for handling when the LLM hallucinates a tool name that doesn't exist.
         * Default behavior: throws an exception.
         * Custom strategy: return a ToolExecutionResultMessage guiding the LLM to retry.
         *
         * Example:
         *   .hallucinatedToolNameStrategy(req ->
         *       ToolExecutionResultMessage.from(req, "Tool '" + req.name() + "' does not exist. Available tools: ..."))
         */
        public Builder hallucinatedToolNameStrategy(
                Function<ToolExecutionRequest, ToolExecutionResultMessage> strategy) {
            this.hallucinatedToolNameStrategy = strategy;
            return this;
        }

        /**
         * Maximum number of sequential tool invocations allowed per LLM turn.
         * Safety limit to prevent infinite tool-calling loops.
         * Default in LangChain4j is typically 10.
         */
        public Builder maxSequentialToolExecutions(int max) {
            this.maxSequentialToolExecutions = max;
            return this;
        }

        // --- Request transformation ---

        /**
         * Transform the ChatRequest before it is sent to the LLM.
         * Use for: injecting headers, modifying temperature per-call, adding metadata.
         */
        public Builder chatRequestTransformer(Function<ChatRequest, ChatRequest> transformer) {
            this.chatRequestTransformer = transformer;
            return this;
        }

        // --- Observability & execution ---

        /**
         * AgentListener for observing agent invocations.
         * Called before/after each agent execution for logging, metrics, tracing.
         */
        public Builder listener(AgentListener listener) {
            this.listener = listener;
            return this;
        }

        /**
         * Execute this agent asynchronously in a separate thread.
         * Useful when agents in a sequence are independent and can run in parallel.
         * The AgenticScope blocks only when a subsequent agent needs this agent's output.
         */
        public Builder async(boolean async) {
            this.async = async;
            return this;
        }

        // --- Build ---

        public AgentSpec build() {
            if (outputKey == null || outputKey.isBlank()) {
                throw new IllegalArgumentException("outputKey is required for agent: " + agentName);
            }
            if (contentRetriever != null && retrievalAugmentor != null) {
                throw new IllegalArgumentException(
                        "Only one of [contentRetriever, retrievalAugmentor] can be set for agent: " + agentName);
            }
            if (chatMemory != null && chatMemoryProvider != null) {
                throw new IllegalArgumentException(
                        "Only one of [chatMemory, chatMemoryProvider] can be set for agent: " + agentName);
            }
            return new AgentSpec(this);
        }
    }
}