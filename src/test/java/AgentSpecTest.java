package com.db.clm.kyc.ai.config;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agentic.AgentListener;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.guardrail.InputGuardrail;
import dev.langchain4j.service.guardrail.OutputGuardrail;
import dev.langchain4j.service.output.ToolExecutionResultMessage;
import dev.langchain4j.service.tool.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AgentSpecTest {

    // =========================================================================
    //  Minimal build
    // =========================================================================

    @Nested
    @DisplayName("Minimal spec (only required fields)")
    class MinimalSpec {

        @Test
        @DisplayName("should build with just agentName, description, and outputKey")
        void minimalBuild() {
            AgentSpec spec = AgentSpec.of("test-agent", "A test agent")
                    .outputKey("result")
                    .build();

            assertThat(spec.getAgentName()).isEqualTo("test-agent");
            assertThat(spec.getDescription()).isEqualTo("A test agent");
            assertThat(spec.getOutputKey()).isEqualTo("result");
            assertThat(spec.getInputKeys()).isEmpty();
        }

        @Test
        @DisplayName("should default all optional fields to null/empty/false")
        void minimalDefaults() {
            AgentSpec spec = AgentSpec.of("test-agent", "desc")
                    .outputKey("out")
                    .build();

            // Models
            assertThat(spec.getChatModel()).isNull();
            assertThat(spec.getStreamingChatModel()).isNull();
            assertThat(spec.hasModelOverride()).isFalse();
            assertThat(spec.hasStreamingModel()).isFalse();

            // Tools
            assertThat(spec.getTools()).isEmpty();
            assertThat(spec.getToolsWithDescriptions()).isNull();
            assertThat(spec.getToolProvider()).isNull();
            assertThat(spec.hasTools()).isFalse();
            assertThat(spec.hasToolsWithDescriptions()).isFalse();
            assertThat(spec.hasToolProvider()).isFalse();

            // Memory
            assertThat(spec.getChatMemory()).isNull();
            assertThat(spec.getChatMemoryProvider()).isNull();
            assertThat(spec.getSummarizedContextAgents()).isNull();
            assertThat(spec.hasChatMemory()).isFalse();
            assertThat(spec.hasChatMemoryProvider()).isFalse();
            assertThat(spec.hasSummarizedContext()).isFalse();

            // RAG
            assertThat(spec.getContentRetriever()).isNull();
            assertThat(spec.getRetrievalAugmentor()).isNull();
            assertThat(spec.hasContentRetriever()).isFalse();
            assertThat(spec.hasRetrievalAugmentor()).isFalse();

            // Guardrails (instances)
            assertThat(spec.getInputGuardrails()).isEmpty();
            assertThat(spec.getOutputGuardrails()).isEmpty();
            assertThat(spec.hasInputGuardrails()).isFalse();
            assertThat(spec.hasOutputGuardrails()).isFalse();

            // Guardrails (classes)
            assertThat(spec.getInputGuardrailClasses()).isEmpty();
            assertThat(spec.getOutputGuardrailClasses()).isEmpty();
            assertThat(spec.hasInputGuardrailClasses()).isFalse();
            assertThat(spec.hasOutputGuardrailClasses()).isFalse();

            // Tool behavior
            assertThat(spec.getHallucinatedToolNameStrategy()).isNull();
            assertThat(spec.getMaxSequentialToolExecutions()).isNull();
            assertThat(spec.hasHallucinatedToolNameStrategy()).isFalse();
            assertThat(spec.hasMaxSequentialToolExecutions()).isFalse();

            // Request transformer
            assertThat(spec.getChatRequestTransformer()).isNull();
            assertThat(spec.hasChatRequestTransformer()).isFalse();

            // Observability
            assertThat(spec.getListener()).isNull();
            assertThat(spec.getAsync()).isNull();
            assertThat(spec.hasListener()).isFalse();
            assertThat(spec.isAsync()).isFalse();
        }
    }

    // =========================================================================
    //  Full build with all fields
    // =========================================================================

    @Nested
    @DisplayName("Fully configured spec (all fields set)")
    class FullSpec {

        private final ChatModel chatModel = mock(ChatModel.class);
        private final StreamingChatModel streamingChatModel = mock(StreamingChatModel.class);
        private final Object toolBean = new Object();
        private final ToolProvider toolProvider = mock(ToolProvider.class);
        private final ChatMemory chatMemory = mock(ChatMemory.class);
        private final ContentRetriever contentRetriever = mock(ContentRetriever.class);
        private final InputGuardrail inputGuardrail = mock(InputGuardrail.class);
        private final OutputGuardrail outputGuardrail = mock(OutputGuardrail.class);
        private final Function<ToolExecutionRequest, ToolExecutionResultMessage> hallucinationStrategy = req -> null;
        private final Function<ChatRequest, ChatRequest> chatRequestTransformer = req -> req;
        private final AgentListener listener = mock(AgentListener.class);

        @Test
        @DisplayName("should build with all optional fields and return correct values")
        void fullBuild() {
            Map<Object, String> toolsWithDesc = Map.of(toolBean, "my tool description");

            AgentSpec spec = AgentSpec.of("full-agent", "Full description")
                    .inputs("input1", "input2")
                    .outputKey("output1")
                    .chatModel(chatModel)
                    .streamingChatModel(streamingChatModel)
                    .tools(toolBean)
                    .toolsWithDescriptions(toolsWithDesc)
                    .toolProvider(toolProvider)
                    .chatMemory(chatMemory)
                    // Note: can't set chatMemoryProvider with chatMemory — tested separately
                    .summarizedContext("agentA", "agentB")
                    .contentRetriever(contentRetriever)
                    // Note: can't set retrievalAugmentor with contentRetriever — tested separately
                    .inputGuardrails(inputGuardrail)
                    .outputGuardrails(outputGuardrail)
                    .inputGuardrailClasses(TestInputGuardrail.class)
                    .outputGuardrailClasses(TestOutputGuardrail.class)
                    .hallucinatedToolNameStrategy(hallucinationStrategy)
                    .maxSequentialToolExecutions(15)
                    .chatRequestTransformer(chatRequestTransformer)
                    .listener(listener)
                    .async(true)
                    .build();

            // Core
            assertThat(spec.getAgentName()).isEqualTo("full-agent");
            assertThat(spec.getDescription()).isEqualTo("Full description");
            assertThat(spec.getInputKeys()).containsExactly("input1", "input2");
            assertThat(spec.getOutputKey()).isEqualTo("output1");

            // Models
            assertThat(spec.getChatModel()).isSameAs(chatModel);
            assertThat(spec.getStreamingChatModel()).isSameAs(streamingChatModel);
            assertThat(spec.hasModelOverride()).isTrue();
            assertThat(spec.hasStreamingModel()).isTrue();

            // Tools
            assertThat(spec.getTools()).containsExactly(toolBean);
            assertThat(spec.hasTools()).isTrue();
            assertThat(spec.getToolsWithDescriptions()).isEqualTo(toolsWithDesc);
            assertThat(spec.hasToolsWithDescriptions()).isTrue();
            assertThat(spec.getToolProvider()).isSameAs(toolProvider);
            assertThat(spec.hasToolProvider()).isTrue();

            // Memory
            assertThat(spec.getChatMemory()).isSameAs(chatMemory);
            assertThat(spec.hasChatMemory()).isTrue();
            assertThat(spec.getSummarizedContextAgents()).containsExactly("agentA", "agentB");
            assertThat(spec.hasSummarizedContext()).isTrue();

            // RAG
            assertThat(spec.getContentRetriever()).isSameAs(contentRetriever);
            assertThat(spec.hasContentRetriever()).isTrue();

            // Guardrails (instances)
            assertThat(spec.getInputGuardrails()).containsExactly(inputGuardrail);
            assertThat(spec.hasInputGuardrails()).isTrue();
            assertThat(spec.getOutputGuardrails()).containsExactly(outputGuardrail);
            assertThat(spec.hasOutputGuardrails()).isTrue();

            // Guardrails (classes)
            assertThat(spec.getInputGuardrailClasses()).containsExactly(TestInputGuardrail.class);
            assertThat(spec.hasInputGuardrailClasses()).isTrue();
            assertThat(spec.getOutputGuardrailClasses()).containsExactly(TestOutputGuardrail.class);
            assertThat(spec.hasOutputGuardrailClasses()).isTrue();

            // Tool behavior
            assertThat(spec.getHallucinatedToolNameStrategy()).isSameAs(hallucinationStrategy);
            assertThat(spec.hasHallucinatedToolNameStrategy()).isTrue();
            assertThat(spec.getMaxSequentialToolExecutions()).isEqualTo(15);
            assertThat(spec.hasMaxSequentialToolExecutions()).isTrue();

            // Request transformer
            assertThat(spec.getChatRequestTransformer()).isSameAs(chatRequestTransformer);
            assertThat(spec.hasChatRequestTransformer()).isTrue();

            // Observability
            assertThat(spec.getListener()).isSameAs(listener);
            assertThat(spec.hasListener()).isTrue();
            assertThat(spec.getAsync()).isTrue();
            assertThat(spec.isAsync()).isTrue();
        }

        @Test
        @DisplayName("should return false for isAsync when async is explicitly false")
        void asyncFalse() {
            AgentSpec spec = AgentSpec.of("agent", "desc")
                    .outputKey("out")
                    .async(false)
                    .build();

            assertThat(spec.getAsync()).isFalse();
            assertThat(spec.isAsync()).isFalse();
        }
    }

    // =========================================================================
    //  Builder with chatMemoryProvider (instead of chatMemory)
    // =========================================================================

    @Test
    @DisplayName("should accept chatMemoryProvider when chatMemory is not set")
    void chatMemoryProviderOnly() {
        ChatMemoryProvider provider = mock(ChatMemoryProvider.class);
        AgentSpec spec = AgentSpec.of("agent", "desc")
                .outputKey("out")
                .chatMemoryProvider(provider)
                .build();

        assertThat(spec.getChatMemoryProvider()).isSameAs(provider);
        assertThat(spec.hasChatMemoryProvider()).isTrue();
        assertThat(spec.getChatMemory()).isNull();
        assertThat(spec.hasChatMemory()).isFalse();
    }

    // =========================================================================
    //  Builder with retrievalAugmentor (instead of contentRetriever)
    // =========================================================================

    @Test
    @DisplayName("should accept retrievalAugmentor when contentRetriever is not set")
    void retrievalAugmentorOnly() {
        RetrievalAugmentor augmentor = mock(RetrievalAugmentor.class);
        AgentSpec spec = AgentSpec.of("agent", "desc")
                .outputKey("out")
                .retrievalAugmentor(augmentor)
                .build();

        assertThat(spec.getRetrievalAugmentor()).isSameAs(augmentor);
        assertThat(spec.hasRetrievalAugmentor()).isTrue();
        assertThat(spec.getContentRetriever()).isNull();
        assertThat(spec.hasContentRetriever()).isFalse();
    }

    // =========================================================================
    //  ToolsWithDescriptions edge: null vs empty map
    // =========================================================================

    @Test
    @DisplayName("hasToolsWithDescriptions returns false when null (default)")
    void toolsWithDescNullDefault() {
        AgentSpec spec = AgentSpec.of("agent", "desc")
                .outputKey("out")
                .build();
        assertThat(spec.hasToolsWithDescriptions()).isFalse();
    }

    @Test
    @DisplayName("hasToolsWithDescriptions returns false for empty map")
    void toolsWithDescEmptyMap() {
        AgentSpec spec = AgentSpec.of("agent", "desc")
                .outputKey("out")
                .toolsWithDescriptions(Map.of())
                .build();
        assertThat(spec.hasToolsWithDescriptions()).isFalse();
    }

    // =========================================================================
    //  Validation: missing outputKey
    // =========================================================================

    @Nested
    @DisplayName("Build validation")
    class ValidationTests {

        @Test
        @DisplayName("should throw when outputKey is null")
        void nullOutputKey() {
            assertThatThrownBy(() -> AgentSpec.of("agent", "desc").build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outputKey is required for agent: agent");
        }

        @Test
        @DisplayName("should throw when outputKey is blank")
        void blankOutputKey() {
            assertThatThrownBy(() -> AgentSpec.of("agent", "desc").outputKey("   ").build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outputKey is required");
        }

        @Test
        @DisplayName("should throw when both contentRetriever and retrievalAugmentor are set")
        void contentRetrieverAndAugmentorConflict() {
            ContentRetriever retriever = mock(ContentRetriever.class);
            RetrievalAugmentor augmentor = mock(RetrievalAugmentor.class);

            assertThatThrownBy(() -> AgentSpec.of("agent", "desc")
                    .outputKey("out")
                    .contentRetriever(retriever)
                    .retrievalAugmentor(augmentor)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Only one of [contentRetriever, retrievalAugmentor]");
        }

        @Test
        @DisplayName("should throw when both chatMemory and chatMemoryProvider are set")
        void chatMemoryConflict() {
            ChatMemory memory = mock(ChatMemory.class);
            ChatMemoryProvider provider = mock(ChatMemoryProvider.class);

            assertThatThrownBy(() -> AgentSpec.of("agent", "desc")
                    .outputKey("out")
                    .chatMemory(memory)
                    .chatMemoryProvider(provider)
                    .build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Only one of [chatMemory, chatMemoryProvider]");
        }
    }

    // =========================================================================
    //  toString coverage
    // =========================================================================

    @Nested
    @DisplayName("toString output")
    class ToStringTests {

        @Test
        @DisplayName("minimal spec toString")
        void minimalToString() {
            AgentSpec spec = AgentSpec.of("agent", "desc")
                    .inputs("in1")
                    .outputKey("out")
                    .build();

            String str = spec.toString();
            assertThat(str).isEqualTo("agent [in1] → out");
        }

        @Test
        @DisplayName("full spec toString includes all enabled features")
        void fullToString() {
            AgentSpec spec = AgentSpec.of("agent", "desc")
                    .inputs("in1", "in2")
                    .outputKey("out")
                    .tools(new Object(), new Object())
                    .toolProvider(mock(ToolProvider.class))
                    .chatMemory(mock(ChatMemory.class))
                    .contentRetriever(mock(ContentRetriever.class))
                    .inputGuardrails(mock(InputGuardrail.class))
                    .outputGuardrails(mock(OutputGuardrail.class), mock(OutputGuardrail.class))
                    .async(true)
                    .build();

            String str = spec.toString();
            assertThat(str).contains("agent [in1,in2] → out");
            assertThat(str).contains("tools=2");
            assertThat(str).contains("+toolProvider");
            assertThat(str).contains("+memory");
            assertThat(str).contains("+rag");
            assertThat(str).contains("+inputGuardrails=1");
            assertThat(str).contains("+outputGuardrails=2");
            assertThat(str).contains("[async]");
        }

        @Test
        @DisplayName("toString with memory via chatMemoryProvider path")
        void toStringWithMemoryProvider() {
            AgentSpec spec = AgentSpec.of("agent", "desc")
                    .outputKey("out")
                    .chatMemoryProvider(mock(ChatMemoryProvider.class))
                    .build();

            assertThat(spec.toString()).contains("+memory");
        }

        @Test
        @DisplayName("toString with rag via retrievalAugmentor path")
        void toStringWithRetrievalAugmentor() {
            AgentSpec spec = AgentSpec.of("agent", "desc")
                    .outputKey("out")
                    .retrievalAugmentor(mock(RetrievalAugmentor.class))
                    .build();

            assertThat(spec.toString()).contains("+rag");
        }

        @Test
        @DisplayName("toString without async when not set")
        void toStringNoAsync() {
            AgentSpec spec = AgentSpec.of("agent", "desc")
                    .outputKey("out")
                    .build();

            assertThat(spec.toString()).doesNotContain("[async]");
        }

        @Test
        @DisplayName("toString without async when explicitly false")
        void toStringAsyncFalse() {
            AgentSpec spec = AgentSpec.of("agent", "desc")
                    .outputKey("out")
                    .async(false)
                    .build();

            assertThat(spec.toString()).doesNotContain("[async]");
        }
    }

    // =========================================================================
    //  Builder fluency — each method returns Builder
    // =========================================================================

    @Test
    @DisplayName("all builder methods return the builder instance for fluent chaining")
    void builderFluency() {
        AgentSpec.Builder builder = AgentSpec.of("agent", "desc");

        // Each call should return the same builder
        assertThat(builder.inputs("a")).isSameAs(builder);
        assertThat(builder.outputKey("out")).isSameAs(builder);
        assertThat(builder.chatModel(mock(ChatModel.class))).isSameAs(builder);
        assertThat(builder.streamingChatModel(mock(StreamingChatModel.class))).isSameAs(builder);
        assertThat(builder.tools(new Object())).isSameAs(builder);
        assertThat(builder.toolsWithDescriptions(Map.of())).isSameAs(builder);
        assertThat(builder.toolProvider(mock(ToolProvider.class))).isSameAs(builder);
        assertThat(builder.chatMemory(mock(ChatMemory.class))).isSameAs(builder);
        // reset chatMemory to avoid conflict:
        // (builder is not immutable, so set chatMemory back — but we can't null it in the builder)
        // Instead, we test chatMemoryProvider in a separate builder:
        assertThat(builder.summarizedContext("x")).isSameAs(builder);
        assertThat(builder.contentRetriever(mock(ContentRetriever.class))).isSameAs(builder);
        assertThat(builder.inputGuardrails(mock(InputGuardrail.class))).isSameAs(builder);
        assertThat(builder.outputGuardrails(mock(OutputGuardrail.class))).isSameAs(builder);
        assertThat(builder.inputGuardrailClasses(TestInputGuardrail.class)).isSameAs(builder);
        assertThat(builder.outputGuardrailClasses(TestOutputGuardrail.class)).isSameAs(builder);
        assertThat(builder.hallucinatedToolNameStrategy(req -> null)).isSameAs(builder);
        assertThat(builder.maxSequentialToolExecutions(5)).isSameAs(builder);
        assertThat(builder.chatRequestTransformer(req -> req)).isSameAs(builder);
        assertThat(builder.listener(mock(AgentListener.class))).isSameAs(builder);
        assertThat(builder.async(true)).isSameAs(builder);
    }

    @Test
    @DisplayName("chatMemoryProvider builder returns Builder")
    void chatMemoryProviderBuilderFluency() {
        AgentSpec.Builder builder = AgentSpec.of("agent", "desc");
        assertThat(builder.chatMemoryProvider(mock(ChatMemoryProvider.class))).isSameAs(builder);
    }

    @Test
    @DisplayName("retrievalAugmentor builder returns Builder")
    void retrievalAugmentorBuilderFluency() {
        AgentSpec.Builder builder = AgentSpec.of("agent", "desc");
        assertThat(builder.retrievalAugmentor(mock(RetrievalAugmentor.class))).isSameAs(builder);
    }

    // =========================================================================
    //  Multiple tools accumulate
    // =========================================================================

    @Test
    @DisplayName("multiple calls to tools() accumulate all instances")
    void toolsAccumulate() {
        Object tool1 = new Object();
        Object tool2 = new Object();
        Object tool3 = new Object();

        AgentSpec spec = AgentSpec.of("agent", "desc")
                .outputKey("out")
                .tools(tool1, tool2)
                .tools(tool3)
                .build();

        assertThat(spec.getTools()).containsExactly(tool1, tool2, tool3);
    }

    // =========================================================================
    //  Multiple guardrails accumulate
    // =========================================================================

    @Test
    @DisplayName("multiple calls to guardrails accumulate all instances")
    void guardrailsAccumulate() {
        InputGuardrail ig1 = mock(InputGuardrail.class);
        InputGuardrail ig2 = mock(InputGuardrail.class);
        OutputGuardrail og1 = mock(OutputGuardrail.class);

        AgentSpec spec = AgentSpec.of("agent", "desc")
                .outputKey("out")
                .inputGuardrails(ig1)
                .inputGuardrails(ig2)
                .outputGuardrails(og1)
                .build();

        assertThat(spec.getInputGuardrails()).containsExactly(ig1, ig2);
        assertThat(spec.getOutputGuardrails()).containsExactly(og1);
    }

    // =========================================================================
    //  Immutability — returned lists are unmodifiable
    // =========================================================================

    @Test
    @DisplayName("returned lists are immutable copies")
    void immutability() {
        AgentSpec spec = AgentSpec.of("agent", "desc")
                .inputs("in1")
                .outputKey("out")
                .tools(new Object())
                .inputGuardrails(mock(InputGuardrail.class))
                .outputGuardrails(mock(OutputGuardrail.class))
                .inputGuardrailClasses(TestInputGuardrail.class)
                .outputGuardrailClasses(TestOutputGuardrail.class)
                .summarizedContext("x")
                .build();

        assertThatThrownBy(() -> spec.getInputKeys().add("hack"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> spec.getTools().add(new Object()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> spec.getInputGuardrails().add(mock(InputGuardrail.class)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> spec.getOutputGuardrails().add(mock(OutputGuardrail.class)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> spec.getInputGuardrailClasses().add(TestInputGuardrail.class))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> spec.getOutputGuardrailClasses().add(TestOutputGuardrail.class))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> spec.getSummarizedContextAgents().add("hack"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // =========================================================================
    //  Test guardrail stub classes (for class-based guardrail testing)
    // =========================================================================

    static abstract class TestInputGuardrail implements InputGuardrail {}
    static abstract class TestOutputGuardrail implements OutputGuardrail {}
}
