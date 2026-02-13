package com.eainde.agent;

package com.db.clm.kyc.ai.config;

import com.db.clm.kyc.ai.prompt.PromptService;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.tool.ToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.function.Predicate;

/**
 * Factory that builds LangChain4j agents from {@link AgentSpec} definitions.
 * Maps every AgentSpec field to the corresponding AgentBuilder method.
 *
 * <p>Centralizes all agent construction logic — the rest of the codebase
 * works only with AgentSpec (declarative) and composed workflows.</p>
 *
 * <h3>Capabilities mapped (matching AgentBuilder API):</h3>
 * <pre>
 * ┌─────────────────────────────────┬─────────────────────────────────────────────┐
 * │ AgentSpec field                 │ AgentBuilder method                         │
 * ├─────────────────────────────────┼─────────────────────────────────────────────┤
 * │ agentName                       │ .name()                                     │
 * │ description                     │ .description()                              │
 * │ (from PromptService)            │ .systemMessage() / .userMessage()           │
 * │ inputKeys                       │ .inputKey()                                 │
 * │ outputKey                       │ .outputKey()                                │
 * │ chatModel                       │ .chatModel()                                │
 * │ streamingChatModel              │ .streamingChatModel()                       │
 * │ tools                           │ .tools(Object...)                           │
 * │ toolsWithDescriptions           │ .tools(Map)                                 │
 * │ toolProvider                    │ .toolProvider()                             │
 * │ chatMemory                      │ .chatMemory()                               │
 * │ chatMemoryProvider              │ .chatMemoryProvider()                       │
 * │ summarizedContextAgents         │ .summarizedContext()                        │
 * │ contentRetriever                │ .contentRetriever()                         │
 * │ retrievalAugmentor              │ .retrievalAugmentor()                       │
 * │ inputGuardrails                 │ .inputGuardrails()                          │
 * │ outputGuardrails                │ .outputGuardrails()                         │
 * │ hallucinatedToolNameStrategy    │ .hallucinatedToolNameStrategy()             │
 * │ maxSequentialToolExecutions     │ .maxSequentialToolExecutions()              │
 * │ chatRequestTransformer          │ .chatRequestTransformer()                   │
 * │ listener                        │ .listener()                                 │
 * │ async                           │ .async()                                    │
 * └─────────────────────────────────┴─────────────────────────────────────────────┘
 * </pre>
 */
@Component
public class AgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);

    private final ChatModel defaultChatModel;
    private final PromptService promptService;

    public AgentFactory(ChatModel defaultChatModel, PromptService promptService) {
        this.defaultChatModel = defaultChatModel;
        this.promptService = promptService;
    }

    // =========================================================================
    //  Core: Build a single agent from a spec
    // =========================================================================

    /**
     * Creates an UntypedAgent from a spec, mapping all configured fields
     * to the corresponding AgentBuilder methods.
     */
    public UntypedAgent create(AgentSpec spec) {
        log.debug("Building agent: {}", spec);

        var builder = AgenticServices.agentBuilder()
                .chatModel(spec.hasModelOverride() ? spec.getChatModel() : defaultChatModel)
                .name(spec.getAgentName())
                .description(spec.getDescription())
                .systemMessage(promptService.getSystemPrompt(spec.getAgentName()))
                .userMessage(promptService.getUserPrompt(spec.getAgentName()))
                .returnType(String.class)
                .outputKey(spec.getOutputKey());

        // --- Input keys ---
        for (String inputKey : spec.getInputKeys()) {
            builder.inputKey(String.class, inputKey);
        }

        // --- Streaming model ---
        if (spec.hasStreamingModel()) {
            builder.streamingChatModel(spec.getStreamingChatModel());
        }

        // --- Tools ---
        if (spec.hasTools()) {
            builder.tools(spec.getTools());
        }
        if (spec.hasToolsWithDescriptions()) {
            builder.tools(spec.getToolsWithDescriptions());
        }
        if (spec.hasToolProvider()) {
            builder.toolProvider(spec.getToolProvider());
        }

        // --- Memory ---
        if (spec.hasChatMemory()) {
            builder.chatMemory(spec.getChatMemory());
        }
        if (spec.hasChatMemoryProvider()) {
            builder.chatMemoryProvider(spec.getChatMemoryProvider());
        }
        if (spec.hasSummarizedContext()) {
            builder.summarizedContext(spec.getSummarizedContextAgents().toArray(new String[0]));
        }

        // --- RAG ---
        if (spec.hasContentRetriever()) {
            builder.contentRetriever(spec.getContentRetriever());
        }
        if (spec.hasRetrievalAugmentor()) {
            builder.retrievalAugmentor(spec.getRetrievalAugmentor());
        }

        // --- Guardrails ---
        if (spec.hasInputGuardrails()) {
            builder.inputGuardrails(spec.getInputGuardrails());
        }
        if (spec.hasOutputGuardrails()) {
            builder.outputGuardrails(spec.getOutputGuardrails());
        }

        // --- Tool execution behavior ---
        if (spec.hasHallucinatedToolNameStrategy()) {
            builder.hallucinatedToolNameStrategy(spec.getHallucinatedToolNameStrategy());
        }
        if (spec.hasMaxSequentialToolExecutions()) {
            builder.maxSequentialToolExecutions(spec.getMaxSequentialToolExecutions());
        }

        // --- Request transformation ---
        if (spec.hasChatRequestTransformer()) {
            builder.chatRequestTransformer(spec.getChatRequestTransformer());
        }

        // --- Observability & execution ---
        if (spec.hasListener()) {
            builder.listener(spec.getListener());
        }
        if (spec.isAsync()) {
            builder.async(true);
        }

        UntypedAgent agent = builder.build();
        log.debug("Agent built: {} [tools={}, memory={}, rag={}, guardrails={}]",
                spec.getAgentName(), spec.hasTools(), spec.hasChatMemory() || spec.hasChatMemoryProvider(),
                spec.hasContentRetriever() || spec.hasRetrievalAugmentor(),
                spec.hasInputGuardrails() || spec.hasOutputGuardrails());

        return agent;
    }

    /** Build multiple agents from specs. */
    public UntypedAgent[] createAll(AgentSpec... specs) {
        return Arrays.stream(specs).map(this::create).toArray(UntypedAgent[]::new);
    }

    // =========================================================================
    //  Composition: Sequence
    // =========================================================================

    /** Sequential workflow (untyped). */
    public UntypedAgent sequence(String outputKey, UntypedAgent... agents) {
        return AgenticServices.sequenceBuilder()
                .subAgents(agents)
                .outputKey(outputKey)
                .build();
    }

    /** Sequential workflow (typed). */
    public <T> T sequence(Class<T> type, String outputKey, UntypedAgent... agents) {
        return AgenticServices.sequenceBuilder(type)
                .subAgents(agents)
                .outputKey(outputKey)
                .build();
    }

    // =========================================================================
    //  Composition: Loop
    // =========================================================================

    /** Loop — exit condition checked after each agent invocation. */
    public UntypedAgent loop(int maxIterations,
                             Predicate<dev.langchain4j.agentic.AgenticScope> exitCondition,
                             AgentSpec... specs) {
        return AgenticServices.loopBuilder()
                .subAgents(createAll(specs))
                .maxIterations(maxIterations)
                .exitCondition(exitCondition)
                .build();
    }

    /** Loop — exit condition checked only after a full cycle of all agents. */
    public UntypedAgent loopAtEnd(int maxIterations,
                                  Predicate<dev.langchain4j.agentic.AgenticScope> exitCondition,
                                  AgentSpec... specs) {
        return AgenticServices.loopBuilder()
                .subAgents(createAll(specs))
                .maxIterations(maxIterations)
                .testExitAtLoopEnd(true)
                .exitCondition(exitCondition)
                .build();
    }

    /** Loop with pre-built agents. */
    public UntypedAgent loop(int maxIterations,
                             Predicate<dev.langchain4j.agentic.AgenticScope> exitCondition,
                             UntypedAgent... agents) {
        return AgenticServices.loopBuilder()
                .subAgents(agents)
                .maxIterations(maxIterations)
                .exitCondition(exitCondition)
                .build();
    }

    // =========================================================================
    //  Composition: Parallel
    // =========================================================================

    /** Parallel workflow — agents execute concurrently. */
    public UntypedAgent parallel(UntypedAgent... agents) {
        return AgenticServices.parallelBuilder()
                .subAgents(agents)
                .build();
    }

    /** Parallel from specs. */
    public UntypedAgent parallel(AgentSpec... specs) {
        return AgenticServices.parallelBuilder()
                .subAgents(createAll(specs))
                .build();
    }

    // =========================================================================
    //  Composition: Conditional
    // =========================================================================

    /**
     * Conditional workflow — routes to different agents based on a condition.
     * Use the returned builder to define routes.
     *
     * Example:
     *   agentFactory.conditional()
     *       .route(scope -> scope.readState("tier", "").equals("Primary"), primaryAgent)
     *       .route(scope -> scope.readState("tier", "").equals("Secondary"), secondaryAgent)
     *       .defaultAgent(fallbackAgent)
     *       .build();
     */
    public <T> T conditional(Class<T> type) {
        return (T) AgenticServices.conditionalBuilder(type);
    }
}