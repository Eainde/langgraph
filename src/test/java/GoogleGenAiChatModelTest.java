
import com.google.genai.Client;
import com.google.genai.Models;
import com.google.genai.types.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoogleGenAiChatModelTest {

    @Mock
    private Client mockClient;

    @Mock
    private Models mockModels;

    private GoogleGenAiChatModel chatModel;

    @BeforeEach
    void setUp() {
        // Mock the internal 'models' field access of the Client
        // Note: In real Mockito, mocking public fields requires specific setup or getters.
        // Assuming 'client.models' is accessible or we pass a mock that returns it.
        // For this test, we assume the constructor sets 'this.client = builder.client'.
        // We need to ensure 'mockClient.models' returns 'mockModels'.

        // *IMPORTANT*: Since 'models' is a public final field in the SDK, standard Mockito might struggle.
        // If 'models' is final, you might need to wrap the Client or use a darker reflection hack.
        // For this example, we assume we can mock the behavior or that 'models' is a getter in the Builder phase.

        // Reflection set for the 'models' field if it's public final (common in Google SDKs)
        try {
            java.lang.reflect.Field modelsField = Client.class.getDeclaredField("models");
            modelsField.setAccessible(true);
            modelsField.set(mockClient, mockModels);
        } catch (Exception e) {
            // Fallback or ignore if the SDK structure differs in your version
        }

        chatModel = GoogleGenAiChatModel.builder()
                .client(mockClient)
                .modelName("gemini-1.5-pro")
                .temperature(0.5)
                .maxRetries(1) // Keep low for tests
                .build();
    }

    @Test
    void shouldGenerateTextResponse() {
        // GIVEN
        String userText = "Hello, world!";
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from(userText))
                .build();

        // Mock Google Response
        GenerateContentResponse mockResponse = createMockResponse("Hello back!", null);
        when(mockModels.generateContent(eq("gemini-1.5-pro"), anyList(), any(GenerateContentConfig.class)))
                .thenReturn(mockResponse);

        // WHEN
        ChatResponse response = chatModel.doChat(request);

        // THEN
        assertThat(response.aiMessage().text()).isEqualTo("Hello back!");
        assertThat(response.tokenUsage().inputTokenCount()).isEqualTo(10);
        assertThat(response.tokenUsage().outputTokenCount()).isEqualTo(5);

        // Verify Config
        ArgumentCaptor<GenerateContentConfig> configCaptor = ArgumentCaptor.forClass(GenerateContentConfig.class);
        verify(mockModels).generateContent(anyString(), anyList(), configCaptor.capture());
        assertThat(configCaptor.getValue().temperature()).isEqualTo(0.5f);
    }

    @Test
    void shouldHandleSystemMessage() {
        // GIVEN
        ChatRequest request = ChatRequest.builder()
                .messages(
                        SystemMessage.from("Be helpful"),
                        UserMessage.from("Hi")
                )
                .build();

        when(mockModels.generateContent(any(), any(), any())).thenReturn(createMockResponse("Ok", null));

        // WHEN
        chatModel.doChat(request);

        // THEN
        ArgumentCaptor<GenerateContentConfig> configCaptor = ArgumentCaptor.forClass(GenerateContentConfig.class);
        verify(mockModels).generateContent(anyString(), anyList(), configCaptor.capture());

        // Verify System Message was moved to Config
        Content systemInstruction = configCaptor.getValue().systemInstruction();
        assertThat(systemInstruction).isNotNull();
        assertThat(systemInstruction.parts().get().get(0).text()).isEqualTo("Be helpful");
    }

    @Test
    void shouldRequestToolExecution() {
        // GIVEN
        ToolSpecification toolSpec = ToolSpecification.builder()
                .name("get_weather")
                .description("Get weather")
                .build();

        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("Weather in London?"))
                .toolSpecifications(toolSpec)
                .build();

        // Mock Response with Function Call
        FunctionCall fnCall = FunctionCall.builder()
                .name("get_weather")
                .args(Collections.singletonMap("city", "London"))
                .build();

        GenerateContentResponse mockResponse = createMockResponse(null, fnCall);
        when(mockModels.generateContent(any(), any(), any())).thenReturn(mockResponse);

        // WHEN
        ChatResponse response = chatModel.doChat(request);

        // THEN
        assertThat(response.aiMessage().hasToolExecutionRequests()).isTrue();
        ToolExecutionRequest toolReq = response.aiMessage().toolExecutionRequests().get(0);
        assertThat(toolReq.name()).isEqualTo("get_weather");
        assertThat(toolReq.arguments()).contains("London");
    }

    @Test
    void shouldRespectToolChoiceRequired() {
        // GIVEN
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("Do something"))
                .toolSpecifications(ToolSpecification.builder().name("my_tool").build())
                .toolChoice(ToolChoice.REQUIRED) // <--- FORCED
                .build();

        when(mockModels.generateContent(any(), any(), any())).thenReturn(createMockResponse("Ok", null));

        // WHEN
        chatModel.doChat(request);

        // THEN
        ArgumentCaptor<GenerateContentConfig> configCaptor = ArgumentCaptor.forClass(GenerateContentConfig.class);
        verify(mockModels).generateContent(any(), any(), configCaptor.capture());

        ToolConfig toolConfig = configCaptor.getValue().toolConfig();
        assertThat(toolConfig.functionCallingConfig().mode()).isEqualTo("ANY"); // 'ANY' means Required
    }

    @Test
    void shouldApplySafetySettings() {
        // GIVEN
        List<SafetySetting> safetySettings = Arrays.asList(
                SafetySetting.builder().category("HARM_CATEGORY_HATE_SPEECH").threshold("BLOCK_LOW_AND_ABOVE").build()
        );

        GoogleGenAiChatModel safetyModel = GoogleGenAiChatModel.builder()
                .client(mockClient)
                .modelName("gemini-1.5-pro")
                .safetySettings(safetySettings)
                .build();

        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("Hi")).build();
        when(mockModels.generateContent(any(), any(), any())).thenReturn(createMockResponse("Safe", null));

        // WHEN
        safetyModel.doChat(request);

        // THEN
        ArgumentCaptor<GenerateContentConfig> configCaptor = ArgumentCaptor.forClass(GenerateContentConfig.class);
        verify(mockModels).generateContent(any(), any(), configCaptor.capture());

        assertThat(configCaptor.getValue().safetySettings()).hasSize(1);
        assertThat(configCaptor.getValue().safetySettings().get(0).category()).isEqualTo("HARM_CATEGORY_HATE_SPEECH");
    }

    @Test
    void shouldRetryOnFailure() {
        // GIVEN
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("Hi")).build();

        // Throw exception first time, return success second time
        when(mockModels.generateContent(any(), any(), any()))
                .thenThrow(new RuntimeException("API Error"))
                .thenReturn(createMockResponse("Recovered", null));

        // WHEN
        ChatResponse response = chatModel.doChat(request);

        // THEN
        assertThat(response.aiMessage().text()).isEqualTo("Recovered");
        // Verify called twice
        verify(mockModels, times(2)).generateContent(any(), any(), any());
    }

    @Test
    void shouldThrowExceptionAfterMaxRetries() {
        // GIVEN
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("Hi")).build();

        // Always fail
        when(mockModels.generateContent(any(), any(), any()))
                .thenThrow(new RuntimeException("API Error"));

        // WHEN / THEN
        assertThatThrownBy(() -> chatModel.doChat(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Google GenAI call failed");

        // Verify called maxRetries + 1 (1 initial + 1 retry configured in setup)
        verify(mockModels, times(2)).generateContent(any(), any(), any());
    }

    // --- Helper to Create Mock Responses ---
    private GenerateContentResponse createMockResponse(String text, FunctionCall functionCall) {
        // Mocking the complex nested structure of Google Response

        Part.Builder partBuilder = Part.builder();
        if (text != null) partBuilder.text(text);
        if (functionCall != null) partBuilder.functionCall(functionCall);

        Content content = Content.builder()
                .parts(Collections.singletonList(partBuilder.build()))
                .build();

        Candidate candidate = Candidate.builder()
                .content(content)
                .finishReason("STOP")
                .build();

        GenerateContentResponseUsageMetadata metadata = GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(10)
                .candidatesTokenCount(5)
                .build();

        return GenerateContentResponse.builder()
                .candidates(Collections.singletonList(candidate))
                .usageMetadata(metadata)
                .build();
    }
}