
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatParameterUtilTest {

    @Mock
    private NexusChatRequestParameters mockConfig;

    @Test
    void map_shouldReturnEmptyBuilder_whenConfigIsNull() {
        // Act
        ChatRequestParameters result = ChatParameterUtil.map(null);

        // Assert
        assertThat(result).isNotNull();
        // Verify default state (usually nulls or empty collections depending on implementation)
        assertThat(result.stopSequences()).isEmpty();
        assertThat(result.toolSpecifications()).isEmpty();
    }

    @Test
    void map_shouldIgnoreUnsupportedParameters_whenTheyArePresent() {
        // This test confirms that setting "unsupported" fields (which log warnings in the code)
        // does not result in them being set on the final builder.

        // Arrange
        when(mockConfig.getModelName()).thenReturn("gemini-pro");
        when(mockConfig.getMaxOutputTokens()).thenReturn(100);
        when(mockConfig.getChatTemperature()).thenReturn(0.7);
        when(mockConfig.getChatTopP()).thenReturn(0.9);
        when(mockConfig.getChatTopK()).thenReturn(40);
        when(mockConfig.getFrequencyPenalty()).thenReturn(0.5);
        when(mockConfig.getPresencePenalty()).thenReturn(0.5);

        // Act
        ChatRequestParameters result = ChatParameterUtil.map(mockConfig);

        // Assert
        assertThat(result).isNotNull();
        // Since the code logs warnings but DOES NOT set these on the builder,
        // we assert they are null/default in the result.
        assertThat(result.maxOutputTokens()).isNull();
        assertThat(result.temperature()).isNull();
        assertThat(result.topP()).isNull();
        assertThat(result.topK()).isNull();
        assertThat(result.frequencyPenalty()).isNull();
        assertThat(result.presencePenalty()).isNull();
    }

    @Test
    void map_shouldMapStopSequences_whenPresent() {
        // Arrange
        List<String> stops = List.of("STOP", "END");
        when(mockConfig.getStopSequences()).thenReturn(stops);

        // Act
        ChatRequestParameters result = ChatParameterUtil.map(mockConfig);

        // Assert
        assertThat(result.stopSequences()).containsExactlyElementsOf(stops);
    }

    @Test
    void map_shouldMapToolSpecifications_whenPresent() {
        // Arrange
        // Assuming ToolSpecification is a valid type in your classpath
        var mockToolSpec = mock(dev.langchain4j.agent.tool.ToolSpecification.class);
        when(mockConfig.getToolSpecifications()).thenReturn(List.of(mockToolSpec));

        // Act
        ChatRequestParameters result = ChatParameterUtil.map(mockConfig);

        // Assert
        assertThat(result.toolSpecifications()).hasSize(1);
        assertThat(result.toolSpecifications().get(0)).isEqualTo(mockToolSpec);
    }

    @Test
    void map_shouldMapToolChoice_whenPresent() {
        // Arrange
        var mockToolChoice = mock(dev.langchain4j.model.chat.request.ToolChoice.class);
        when(mockConfig.getToolChoice()).thenReturn(mockToolChoice);

        // Act
        ChatRequestParameters result = ChatParameterUtil.map(mockConfig);

        // Assert
        assertThat(result.toolChoice()).isEqualTo(mockToolChoice);
    }

    @Test
    void map_shouldMapResponseSchema_whenValid() {
        // Use try-with-resources to mock the static JsonSchemaUtil
        try (MockedStatic<JsonSchemaUtil> mockedStatic = Mockito.mockStatic(JsonSchemaUtil.class)) {
            // Arrange
            String schemaString = "{\"type\":\"object\"}";
            JsonSchema mockJsonSchema = mock(JsonSchema.class);

            when(mockConfig.getResponseSchema()).thenReturn(schemaString);

            // Mock the static converter
            mockedStatic.when(() -> JsonSchemaUtil.toLangChainSchema(anyString(), anyString()))
                    .thenReturn(mockJsonSchema);

            // Act
            ChatRequestParameters result = ChatParameterUtil.map(mockConfig);

            // Assert
            ResponseFormat format = result.responseFormat();
            assertThat(format).isNotNull();
            assertThat(format.type()).isEqualTo(ResponseFormatType.JSON);
            assertThat(format.jsonSchema()).isEqualTo(mockJsonSchema);
        }
    }

    @Test
    void map_shouldFallbackToBasicJson_whenSchemaParsingFails() {
        // This tests the try/catch block around the JSON Schema parsing
        try (MockedStatic<JsonSchemaUtil> mockedStatic = Mockito.mockStatic(JsonSchemaUtil.class)) {
            // Arrange
            when(mockConfig.getResponseSchema()).thenReturn("INVALID_JSON");

            // Simulate Exception in static utility
            mockedStatic.when(() -> JsonSchemaUtil.toLangChainSchema(anyString(), anyString()))
                    .thenThrow(new RuntimeException("Schema parsing error"));

            // Act
            ChatRequestParameters result = ChatParameterUtil.map(mockConfig);

            // Assert
            ResponseFormat format = result.responseFormat();
            assertThat(format).isNotNull();
            // Should still be JSON type (fallback in catch block)
            assertThat(format.type()).isEqualTo(ResponseFormatType.JSON);
            // But schema should be null because parsing failed
            assertThat(format.jsonSchema()).isNull();
        }
    }
}