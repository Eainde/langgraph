package com.db.clm.kyc.ai.config;

import com.db.clm.kyc.ai.prompt.PromptService;
import dev.langchain4j.agentic.AgenticScope;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.AgentListener;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.guardrail.InputGuardrail;
import dev.langchain4j.service.guardrail.OutputGuardrail;
import dev.langchain4j.service.tool.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AgentFactory} with 100% line coverage.
 *
 * Uses MockedStatic for AgenticServices static factory methods,
 * and verifies that AgentFactory.create() correctly delegates
 * all AgentSpec fields to the underlying builder.
 */
@ExtendWith(MockitoExtension.class)
class AgentFactoryTest {

    @Mock private ChatModel defaultChatModel;
    @Mock private PromptService promptService;

    private AgentFactory agentFactory;

    @BeforeEach
    void setUp() {
        agentFactory = new AgentFactory(defaultChatModel, promptService);
        lenient().when(promptService.getSystemPrompt(any())).thenReturn("system prompt");
        lenient().when(promptService.getUserPrompt(any())).thenReturn("user prompt");
    }

    // =========================================================================
    //  Helper: creates a mock builder chain that returns itself for every call
    // =========================================================================

    @SuppressWarnings("unchecked")
    private <B> B selfReturningMock(Class<B> clazz) {
        B mock = mock(clazz, invocation -> {
            if (invocation.getMethod().getReturnType().isAssignableFrom(clazz)) {
                return invocation.getMock();
            }
            if (invocation.getMethod().getReturnType() == UntypedAgent.class) {
                return mock(UntypedAgent.class);
            }
            return null;
        });
        return mock;
    }

    // =========================================================================
    //  create() — minimal spec
    // =========================================================================

    @Nested
    @DisplayName("create() with minimal spec")
    class CreateMinimal {

        @Test
        @DisplayName("should call builder with core fields and use default chatModel")
        void minimalCreate() {
            // Use an interface-based mock builder to allow deep stubbing
            var mockBuilder = mock(dev.langchain4j.agentic.UntypedAgentBuilder.class, RETURNS_SELF);
            UntypedAgent mockAgent = mock(UntypedAgent.class);
            when(mockBuilder.build()).thenReturn(mockAgent);

            try (MockedStatic<AgenticServices> staticMock = mockStatic(AgenticServices.class)) {
                staticMock.when(AgenticServices::agentBuilder).thenReturn(mockBuilder);

                AgentSpec spec = AgentSpec.of("test-agent", "A test agent")
                        .inputs("sourceText")
                        .outputKey("result")
                        .build();

                UntypedAgent result = agentFactory.create(spec);

                assertThat(result).isSameAs(mockAgent);

                // Verify core builder calls
                verify(mockBuilder).chatModel(defaultChatModel);
                verify(mockBuilder).name("test-agent");
                verify(mockBuilder).description("A test agent");
                verify(mockBuilder).systemMessage("system prompt");
                verify(mockBuilder).userMessage("user prompt");
                verify(mockBuilder).returnType(String.class);
                verify(mockBuilder).outputKey("result");
                verify(mockBuilder).inputKey(String.class, "sourceText");

                // Verify optional features NOT called
                verify(mockBuilder, never()).streamingChatModel(any());
                verify(mockBuilder, never()).tools(any(Object[].class));
                verify(mockBuilder, never()).tools(any(Map.class));
                verify(mockBuilder, never()).toolProvider(any());
                verify(mockBuilder, never()).chatMemory(any());
                verify(mockBuilder, never()).chatMemoryProvider(any());
                verify(mockBuilder, never()).summarizedContext(any(String[].class));
                verify(mockBuilder, never()).contentRetriever(any());
                verify(mockBuilder, never()).retrievalAugmentor(any());
                verify(mockBuilder, never()).inputGuardrails(any(InputGuardrail[].class));
                verify(mockBuilder, never()).outputGuardrails(any(OutputGuardrail[].class));
                verify(mockBuilder, never()).inputGuardrailClasses(any());
                verify(mockBuilder, never()).outputGuardrailClasses(any());
                verify(mockBuilder, never()).hallucinatedToolNameStrategy(any());
                verify(mockBuilder, never()).maxSequentialToolsInvocations(anyInt());
                verify(mockBuilder, never()).chatRequestTransformer(any());
                verify(mockBuilder, never()).listener(any());
                verify(mockBuilder, never()).async(anyBoolean());
                verify(mockBuilder).build();
            }
        }
    }

    // =========================================================================
    //  create() — full spec exercises every conditional branch
    // =========================================================================

    @Nested
    @DisplayName("create() with fully configured spec")
    class CreateFull {

        @Test
        @DisplayName("should delegate all fields to the builder")
        void fullCreate() {
            var mockBuilder = mock(dev.langchain4j.agentic.UntypedAgentBuilder.class, RETURNS_SELF);
            UntypedAgent mockAgent = mock(UntypedAgent.class);
            when(mockBuilder.build()).thenReturn(mockAgent);

            ChatModel overrideModel = mock(ChatModel.class);
            StreamingChatModel streamingModel = mock(StreamingChatModel.class);
            Object toolBean = new Object();
            ToolProvider toolProvider = mock(ToolProvider.class);
            Map<Object, String> toolsWithDesc = Map.of(toolBean, "description");
            ChatMemory chatMemory = mock(ChatMemory.class);
            ContentRetriever retriever = mock(ContentRetriever.class);
            InputGuardrail inputGuardrail = mock(InputGuardrail.class);
            OutputGuardrail outputGuardrail = mock(OutputGuardrail.class);
            AgentListener listener = mock(AgentListener.class);

            AgentSpec spec = AgentSpec.of("full-agent", "full desc")
                    .inputs("in1", "in2")
                    .outputKey("out")
                    .chatModel(overrideModel)
                    .streamingChatModel(streamingModel)
                    .tools(toolBean)
                    .toolsWithDescriptions(toolsWithDesc)
                    .toolProvider(toolProvider)
                    .chatMemory(chatMemory)
                    .summarizedContext("agentA")
                    .contentRetriever(retriever)
                    .inputGuardrails(inputGuardrail)
                    .outputGuardrails(outputGuardrail)
                    .inputGuardrailClasses(StubInputGuardrail.class)
                    .outputGuardrailClasses(StubOutputGuardrail.class)
                    .hallucinatedToolNameStrategy(req -> null)
                    .maxSequentialToolExecutions(10)
                    .chatRequestTransformer(req -> req)
                    .listener(listener)
                    .async(true)
                    .build();

            try (MockedStatic<AgenticServices> staticMock = mockStatic(AgenticServices.class)) {
                staticMock.when(AgenticServices::agentBuilder).thenReturn(mockBuilder);

                UntypedAgent result = agentFactory.create(spec);
                assertThat(result).isSameAs(mockAgent);

                // Model override used instead of default
                verify(mockBuilder).chatModel(overrideModel);
                verify(mockBuilder, never()).chatModel(defaultChatModel);

                // Streaming
                verify(mockBuilder).streamingChatModel(streamingModel);

                // Tools
                verify(mockBuilder).tools(any(Object[].class));
                verify(mockBuilder).tools(toolsWithDesc);
                verify(mockBuilder).toolProvider(toolProvider);

                // Memory
                verify(mockBuilder).chatMemory(chatMemory);
                verify(mockBuilder).summarizedContext(any(String[].class));

                // RAG
                verify(mockBuilder).contentRetriever(retriever);

                // Guardrails
                verify(mockBuilder).inputGuardrails(any(InputGuardrail[].class));
                verify(mockBuilder).outputGuardrails(any(OutputGuardrail[].class));
                verify(mockBuilder).inputGuardrailClasses(any());
                verify(mockBuilder).outputGuardrailClasses(any());

                // Tool behavior
                verify(mockBuilder).hallucinatedToolNameStrategy(any());
                verify(mockBuilder).maxSequentialToolsInvocations(10);

                // Request transformer
                verify(mockBuilder).chatRequestTransformer(any());

                // Observability
                verify(mockBuilder).listener(listener);
                verify(mockBuilder).async(true);

                // Input keys
                verify(mockBuilder).inputKey(String.class, "in1");
                verify(mockBuilder).inputKey(String.class, "in2");
            }
        }

        @Test
        @DisplayName("should delegate chatMemoryProvider and retrievalAugmentor branches")
        void memoryProviderAndAugmentorBranches() {
            var mockBuilder = mock(dev.langchain4j.agentic.UntypedAgentBuilder.class, RETURNS_SELF);
            when(mockBuilder.build()).thenReturn(mock(UntypedAgent.class));

            ChatMemoryProvider memProvider = mock(ChatMemoryProvider.class);
            RetrievalAugmentor augmentor = mock(RetrievalAugmentor.class);

            AgentSpec spec = AgentSpec.of("agent", "desc")
                    .outputKey("out")
                    .chatMemoryProvider(memProvider)
                    .retrievalAugmentor(augmentor)
                    .build();

            try (MockedStatic<AgenticServices> staticMock = mockStatic(AgenticServices.class)) {
                staticMock.when(AgenticServices::agentBuilder).thenReturn(mockBuilder);

                agentFactory.create(spec);

                verify(mockBuilder).chatMemoryProvider(memProvider);
                verify(mockBuilder, never()).chatMemory(any());
                verify(mockBuilder).retrievalAugmentor(augmentor);
                verify(mockBuilder, never()).contentRetriever(any());
            }
        }
    }

    // =========================================================================
    //  createAll()
    // =========================================================================

    @Test
    @DisplayName("createAll should return array of agents from multiple specs")
    void createAll() {
        var mockBuilder = mock(dev.langchain4j.agentic.UntypedAgentBuilder.class, RETURNS_SELF);
        UntypedAgent mockAgent1 = mock(UntypedAgent.class);
        UntypedAgent mockAgent2 = mock(UntypedAgent.class);
        when(mockBuilder.build()).thenReturn(mockAgent1, mockAgent2);

        AgentSpec spec1 = AgentSpec.of("agent1", "desc1").outputKey("o1").build();
        AgentSpec spec2 = AgentSpec.of("agent2", "desc2").outputKey("o2").build();

        try (MockedStatic<AgenticServices> staticMock = mockStatic(AgenticServices.class)) {
            staticMock.when(AgenticServices::agentBuilder).thenReturn(mockBuilder);

            UntypedAgent[] agents = agentFactory.createAll(spec1, spec2);

            assertThat(agents).hasSize(2);
            assertThat(agents[0]).isSameAs(mockAgent1);
            assertThat(agents[1]).isSameAs(mockAgent2);
        }
    }

    // =========================================================================
    //  Composition: sequence (untyped)
    // =========================================================================

    @Test
    @DisplayName("sequence(String, UntypedAgent...) should delegate to sequenceBuilder")
    void sequenceUntyped() {
        UntypedAgent agent1 = mock(UntypedAgent.class);
        UntypedAgent agent2 = mock(UntypedAgent.class);
        UntypedAgent sequenceAgent = mock(UntypedAgent.class);

        @SuppressWarnings("unchecked")
        var seqBuilder = mock(dev.langchain4j.agentic.SequentialAgentService.class, RETURNS_SELF);
        when(seqBuilder.build()).thenReturn(sequenceAgent);

        try (MockedStatic<AgenticServices> staticMock = mockStatic(AgenticServices.class)) {
            staticMock.when(AgenticServices::sequenceBuilder).thenReturn(seqBuilder);

            UntypedAgent result = agentFactory.sequence("finalOutput", agent1, agent2);

            assertThat(result).isSameAs(sequenceAgent);
            verify(seqBuilder).subAgents(agent1, agent2);
            verify(seqBuilder).outputKey("finalOutput");
            verify(seqBuilder).build();
        }
    }

    // =========================================================================
    //  Composition: sequence (typed)
    // =========================================================================

    @Test
    @DisplayName("sequence(Class, String, UntypedAgent...) should delegate to typed sequenceBuilder")
    void sequenceTyped() {
        UntypedAgent agent1 = mock(UntypedAgent.class);
        Runnable typedWorkflow = mock(Runnable.class); // stand-in for typed interface

        @SuppressWarnings("unchecked")
        var seqBuilder = mock(dev.langchain4j.agentic.SequentialAgentService.class, RETURNS_SELF);
        when(seqBuilder.build()).thenReturn(typedWorkflow);

        try (MockedStatic<AgenticServices> staticMock = mockStatic(AgenticServices.class)) {
            staticMock.when(() -> AgenticServices.sequenceBuilder(Runnable.class)).thenReturn(seqBuilder);

            Runnable result = agentFactory.sequence(Runnable.class, "out", agent1);

            assertThat(result).isSameAs(typedWorkflow);
            verify(seqBuilder).subAgents(agent1);
            verify(seqBuilder).outputKey("out");
        }
    }

    // =========================================================================
    //  Composition: loop (from specs)
    // =========================================================================

    @Test
    @DisplayName("loop(int, Predicate, AgentSpec...) should build agents and create loop")
    void loopFromSpecs() {
        var agentBuilder = mock(dev.langchain4j.agentic.UntypedAgentBuilder.class, RETURNS_SELF);
        UntypedAgent mockAgent = mock(UntypedAgent.class);
        when(agentBuilder.build()).thenReturn(mockAgent);

        UntypedAgent loopAgent = mock(UntypedAgent.class);

        @SuppressWarnings("unchecked")
        var loopBuilder = mock(dev.langchain4j.agentic.LoopAgentService.class, RETURNS_SELF);
        when(loopBuilder.build()).thenReturn(loopAgent);

        Predicate<AgenticScope> exitCondition = scope -> true;
        AgentSpec spec = AgentSpec.of("agent", "desc").outputKey("o").build();

        try (MockedStatic<AgenticServices> staticMock = mockStatic(AgenticServices.class)) {
            staticMock.when(AgenticServices::agentBuilder).thenReturn(agentBuilder);
            staticMock.when(AgenticServices::loopBuilder).thenReturn(loopBuilder);

            UntypedAgent result = agentFactory.loop(3, exitCondition, spec);

            assertThat(result).isSameAs(loopAgent);
            verify(loopBuilder).maxIterations(3);
            verify(loopBuilder).exitCondition(exitCondition);
            verify(loopBuilder).build();
        }
    }

    // =========================================================================
    //  Composition: loopAtEnd (from specs)
    // =========================================================================

    @Test
    @DisplayName("loopAtEnd should set testExitAtLoopEnd(true)")
    void loopAtEndFromSpecs() {
        var agentBuilder = mock(dev.langchain4j.agentic.UntypedAgentBuilder.class, RETURNS_SELF);
        when(agentBuilder.build()).thenReturn(mock(UntypedAgent.class));

        UntypedAgent loopAgent = mock(UntypedAgent.class);

        @SuppressWarnings("unchecked")
        var loopBuilder = mock(dev.langchain4j.agentic.LoopAgentService.class, RETURNS_SELF);
        when(loopBuilder.build()).thenReturn(loopAgent);

        Predicate<AgenticScope> exitCondition = scope -> false;
        AgentSpec spec = AgentSpec.of("agent", "desc").outputKey("o").build();

        try (MockedStatic<AgenticServices> staticMock = mockStatic(AgenticServices.class)) {
            staticMock.when(AgenticServices::agentBuilder).thenReturn(agentBuilder);
            staticMock.when(AgenticServices::loopBuilder).thenReturn(loopBuilder);

            UntypedAgent result = agentFactory.loopAtEnd(5, exitCondition, spec);

            assertThat(result).isSameAs(loopAgent);
            verify(loopBuilder).maxIterations(5);
            verify(loopBuilder).testExitAtLoopEnd(true);
            verify(loopBuilder).exitCondition(exitCondition);
        }
    }

    // =========================================================================
    //  Composition: loop (from pre-built agents)
    // =========================================================================

    @Test
    @DisplayName("loop(int, Predicate, UntypedAgent...) should use pre-built agents")
    void loopFromAgents() {
        UntypedAgent agent1 = mock(UntypedAgent.class);
        UntypedAgent agent2 = mock(UntypedAgent.class);
        UntypedAgent loopAgent = mock(UntypedAgent.class);

        @SuppressWarnings("unchecked")
        var loopBuilder = mock(dev.langchain4j.agentic.LoopAgentService.class, RETURNS_SELF);
        when(loopBuilder.build()).thenReturn(loopAgent);

        Predicate<AgenticScope> exitCondition = scope -> true;

        try (MockedStatic<AgenticServices> staticMock = mockStatic(AgenticServices.class)) {
            staticMock.when(AgenticServices::loopBuilder).thenReturn(loopBuilder);

            UntypedAgent result = agentFactory.loop(2, exitCondition, agent1, agent2);

            assertThat(result).isSameAs(loopAgent);
            verify(loopBuilder).subAgents(agent1, agent2);
            verify(loopBuilder).maxIterations(2);
            verify(loopBuilder).exitCondition(exitCondition);
            verify(loopBuilder, never()).testExitAtLoopEnd(anyBoolean());
        }
    }

    // =========================================================================
    //  Composition: parallel (from agents)
    // =========================================================================

    @Test
    @DisplayName("parallel(UntypedAgent...) should delegate to parallelBuilder")
    void parallelFromAgents() {
        UntypedAgent agent1 = mock(UntypedAgent.class);
        UntypedAgent parallelAgent = mock(UntypedAgent.class);

        @SuppressWarnings("unchecked")
        var parBuilder = mock(dev.langchain4j.agentic.ParallelAgentService.class, RETURNS_SELF);
        when(parBuilder.build()).thenReturn(parallelAgent);

        try (MockedStatic<AgenticServices> staticMock = mockStatic(AgenticServices.class)) {
            staticMock.when(AgenticServices::parallelBuilder).thenReturn(parBuilder);

            UntypedAgent result = agentFactory.parallel(agent1);

            assertThat(result).isSameAs(parallelAgent);
            verify(parBuilder).subAgents(agent1);
        }
    }

    // =========================================================================
    //  Composition: parallel (from specs)
    // =========================================================================

    @Test
    @DisplayName("parallel(AgentSpec...) should build agents and create parallel")
    void parallelFromSpecs() {
        var agentBuilder = mock(dev.langchain4j.agentic.UntypedAgentBuilder.class, RETURNS_SELF);
        when(agentBuilder.build()).thenReturn(mock(UntypedAgent.class));

        UntypedAgent parallelAgent = mock(UntypedAgent.class);

        @SuppressWarnings("unchecked")
        var parBuilder = mock(dev.langchain4j.agentic.ParallelAgentService.class, RETURNS_SELF);
        when(parBuilder.build()).thenReturn(parallelAgent);

        AgentSpec spec = AgentSpec.of("agent", "desc").outputKey("o").build();

        try (MockedStatic<AgenticServices> staticMock = mockStatic(AgenticServices.class)) {
            staticMock.when(AgenticServices::agentBuilder).thenReturn(agentBuilder);
            staticMock.when(AgenticServices::parallelBuilder).thenReturn(parBuilder);

            UntypedAgent result = agentFactory.parallel(spec);

            assertThat(result).isSameAs(parallelAgent);
        }
    }

    // =========================================================================
    //  Composition: conditional
    // =========================================================================

    @Test
    @DisplayName("conditional(Class) should delegate to conditionalBuilder")
    void conditional() {
        Object mockConditionalBuilder = new Object();

        try (MockedStatic<AgenticServices> staticMock = mockStatic(AgenticServices.class)) {
            staticMock.when(() -> AgenticServices.conditionalBuilder(Runnable.class))
                    .thenReturn(mockConditionalBuilder);

            Object result = agentFactory.conditional(Runnable.class);

            assertThat(result).isSameAs(mockConditionalBuilder);
        }
    }

    // =========================================================================
    //  Prompt service delegation
    // =========================================================================

    @Test
    @DisplayName("should fetch prompts from PromptService using agent name")
    void promptServiceDelegation() {
        when(promptService.getSystemPrompt("my-agent")).thenReturn("custom system");
        when(promptService.getUserPrompt("my-agent")).thenReturn("custom user");

        var mockBuilder = mock(dev.langchain4j.agentic.UntypedAgentBuilder.class, RETURNS_SELF);
        when(mockBuilder.build()).thenReturn(mock(UntypedAgent.class));

        AgentSpec spec = AgentSpec.of("my-agent", "desc").outputKey("out").build();

        try (MockedStatic<AgenticServices> staticMock = mockStatic(AgenticServices.class)) {
            staticMock.when(AgenticServices::agentBuilder).thenReturn(mockBuilder);

            agentFactory.create(spec);

            verify(promptService).getSystemPrompt("my-agent");
            verify(promptService).getUserPrompt("my-agent");
            verify(mockBuilder).systemMessage("custom system");
            verify(mockBuilder).userMessage("custom user");
        }
    }

    // =========================================================================
    //  Stub guardrail classes for testing
    // =========================================================================

    static abstract class StubInputGuardrail implements InputGuardrail {}
    static abstract class StubOutputGuardrail implements OutputGuardrail {}
}