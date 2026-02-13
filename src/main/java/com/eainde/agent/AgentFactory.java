package com.eainde.agent;

import com.db.clm.kyc.ai.prompt.PromptService;
import dev.langchain4j.agent.AgenticServices;
import dev.langchain4j.agent.UntypedAgent;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.function.Predicate;

/**
 * Factory that builds LangChain4j agents from {@link AgentSpec} definitions.
 *
 * Centralizes all agent construction logic in one place:
 *   - Resolves prompts from DB via PromptService
 *   - Builds UntypedAgents with correct inputKeys/outputKey mapping
 *   - Provides helpers for common workflow patterns (sequence, loop)
 *
 * Usage in config:
 *   UntypedAgent validator = agentFactory.create(validatorSpec);
 *   UntypedAgent pipeline  = agentFactory.sequence(agent1, agent2, agent3);
 *   UntypedAgent reviewed  = agentFactory.loop(scorer, editor, 5, scope -> score >= 0.8);
 */
@Component
public class AgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);

    private final ChatLanguageModel chatModel;
    private final PromptService promptService;

    public AgentFactory(ChatLanguageModel chatModel, PromptService promptService) {
        this.chatModel = chatModel;
        this.promptService = promptService;
    }

    // =========================================================================
    //  Core: Build a single agent from a spec
    // =========================================================================

    /**
     * Creates an UntypedAgent from an AgentSpec.
     * System and user prompts are loaded from the database using spec.agentName.
     */
    public UntypedAgent create(AgentSpec spec) {
        log.debug("Building agent: {}", spec);

        var builder = AgenticServices.agentBuilder()
                .chatModel(chatModel)
                .name(spec.getAgentName())
                .description(spec.getDescription())
                .systemMessage(promptService.getSystemPrompt(spec.getAgentName()))
                .userMessage(promptService.getUserPrompt(spec.getAgentName()))
                .returnType(String.class)
                .outputKey(spec.getOutputKey());

        // Register each input key the agent reads from AgenticScope
        for (String inputKey : spec.getInputKeys()) {
            builder.inputKey(String.class, inputKey);
        }

        return builder.build();
    }

    // =========================================================================
    //  Composition: Sequence
    // =========================================================================

    /**
     * Creates a sequential workflow from multiple agents.
     * Agents execute in order; each agent's output is available to subsequent agents via AgenticScope.
     *
     * @param outputKey the scope variable to return as the final output
     * @param agents    agents to execute in order
     */
    public UntypedAgent sequence(String outputKey, UntypedAgent... agents) {
        return AgenticServices.sequenceBuilder()
                .subAgents(agents)
                .outputKey(outputKey)
                .build();
    }

    /**
     * Creates a typed sequential workflow.
     */
    public <T> T sequence(Class<T> type, String outputKey, UntypedAgent... agents) {
        return AgenticServices.sequenceBuilder(type)
                .subAgents(agents)
                .outputKey(outputKey)
                .build();
    }

    // =========================================================================
    //  Composition: Loop (critic-validator / iterative refinement)
    // =========================================================================

    /**
     * Creates a loop workflow for critic-validator or iterative refinement patterns.
     *
     * The loop runs the given agents in sequence repeatedly until either:
     *   - exitCondition returns true (checked after each agent invocation)
     *   - maxIterations is reached
     *
     * Typical usage — critic + validator loop:
     *   loop(criticSpec, validatorSpec, 5, scope -> scope.readState("score", 0.0) >= 0.8)
     *
     * @param maxIterations maximum number of loop iterations
     * @param exitCondition predicate on AgenticScope; loop exits when true
     * @param specs         agent specs to build and loop over
     */
    public UntypedAgent loop(int maxIterations,
                             Predicate<dev.langchain4j.agent.AgenticScope> exitCondition,
                             AgentSpec... specs) {
        UntypedAgent[] agents = Arrays.stream(specs)
                .map(this::create)
                .toArray(UntypedAgent[]::new);

        return AgenticServices.loopBuilder()
                .subAgents(agents)
                .maxIterations(maxIterations)
                .exitCondition(exitCondition)
                .build();
    }

    /**
     * Overload: loop with pre-built agents (when you need more control over agent construction).
     */
    public UntypedAgent loop(int maxIterations,
                             Predicate<dev.langchain4j.agent.AgenticScope> exitCondition,
                             UntypedAgent... agents) {
        return AgenticServices.loopBuilder()
                .subAgents(agents)
                .maxIterations(maxIterations)
                .exitCondition(exitCondition)
                .build();
    }

    /**
     * Creates a loop that checks exit condition only at the end of each full iteration
     * (all agents run before checking). Useful when you always want the full
     * critic → validator cycle to complete before evaluating.
     */
    public UntypedAgent loopAtEnd(int maxIterations,
                                  Predicate<dev.langchain4j.agent.AgenticScope> exitCondition,
                                  AgentSpec... specs) {
        UntypedAgent[] agents = Arrays.stream(specs)
                .map(this::create)
                .toArray(UntypedAgent[]::new);

        return AgenticServices.loopBuilder()
                .subAgents(agents)
                .maxIterations(maxIterations)
                .testExitAtLoopEnd(true)
                .exitCondition(exitCondition)
                .build();
    }

    // =========================================================================
    //  Composition: Parallel
    // =========================================================================

    /**
     * Creates a parallel workflow — agents execute concurrently.
     */
    public UntypedAgent parallel(UntypedAgent... agents) {
        return AgenticServices.parallelBuilder()
                .subAgents(agents)
                .build();
    }

    // =========================================================================
    //  Shortcut: Build from spec + immediately return
    // =========================================================================

    /**
     * Convenience: build multiple agents from specs at once.
     */
    public UntypedAgent[] createAll(AgentSpec... specs) {
        return Arrays.stream(specs)
                .map(this::create)
                .toArray(UntypedAgent[]::new);
    }
}
