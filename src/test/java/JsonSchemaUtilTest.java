package com.db.clm.kyc.ai.nexus.util;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonSchemaUtilTest {

    // --- Tests for toLangChainSchema ---

    @Test
    void toLangChainSchema_shouldParseSimpleObjectWithPrimitives() {
        // Arrange
        String json = "{\n" +
                "  \"type\": \"object\",\n" +
                "  \"properties\": {\n" +
                "    \"fullName\": { \"type\": \"string\", \"description\": \"The user's name\" },\n" +
                "    \"age\": { \"type\": \"integer\" },\n" +
                "    \"active\": { \"type\": \"boolean\" }\n" +
                "  },\n" +
                "  \"required\": [\"fullName\"]\n" +
                "}";

        // Act
        JsonSchema result = JsonSchemaUtil.toLangChainSchema("Person", json);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("Person");

        // Verify Root is an Object
        assertThat(result.rootElement()).isInstanceOf(JsonObjectSchema.class);
        JsonObjectSchema root = (JsonObjectSchema) result.rootElement();

        // Verify Properties
        assertThat(root.properties()).hasSize(3);
        assertThat(root.properties()).containsKey("fullName");
        assertThat(root.properties()).containsKey("age");
        assertThat(root.properties()).containsKey("active");

        // Verify specific field attributes
        JsonSchemaElement nameElement = root.properties().get("fullName");
        assertThat(nameElement).isInstanceOf(JsonStringSchema.class);
        assertThat(nameElement.description()).isEqualTo("The user's name");

        // Verify Required fields
        assertThat(root.required()).containsExactly("fullName");
    }

    @Test
    void toLangChainSchema_shouldParseNestedObjects() {
        // Arrange
        String json = "{\n" +
                "  \"type\": \"object\",\n" +
                "  \"properties\": {\n" +
                "    \"address\": {\n" +
                "      \"type\": \"object\",\n" +
                "      \"properties\": {\n" +
                "        \"city\": { \"type\": \"string\" }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        // Act
        JsonSchema result = JsonSchemaUtil.toLangChainSchema("AddressTest", json);

        // Assert
        JsonObjectSchema root = (JsonObjectSchema) result.rootElement();
        JsonSchemaElement addressElement = root.properties().get("address");

        assertThat(addressElement).isInstanceOf(JsonObjectSchema.class);
        JsonObjectSchema addressObj = (JsonObjectSchema) addressElement;

        assertThat(addressObj.properties()).containsKey("city");
    }

    @Test
    void toLangChainSchema_shouldParseArrays() {
        // Arrange
        String json = "{\n" +
                "  \"type\": \"object\",\n" +
                "  \"properties\": {\n" +
                "    \"tags\": {\n" +
                "      \"type\": \"array\",\n" +
                "      \"items\": { \"type\": \"string\" }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        // Act
        JsonSchema result = JsonSchemaUtil.toLangChainSchema("ArrayTest", json);

        // Assert
        JsonObjectSchema root = (JsonObjectSchema) result.rootElement();
        JsonSchemaElement tagsElement = root.properties().get("tags");

        assertThat(tagsElement).isInstanceOf(JsonArraySchema.class);
        JsonArraySchema arraySchema = (JsonArraySchema) tagsElement;

        // Verify the items inside the array are Strings
        assertThat(arraySchema.items()).isInstanceOf(JsonStringSchema.class);
    }

    @Test
    void toLangChainSchema_shouldParseEnums() {
        // Arrange
        String json = "{\n" +
                "  \"type\": \"object\",\n" +
                "  \"properties\": {\n" +
                "    \"status\": {\n" +
                "      \"type\": \"string\",\n" +
                "      \"enum\": [\"OPEN\", \"CLOSED\", \"PENDING\"]\n" +
                "    }\n" +
                "  }\n" +
                "}";

        // Act
        JsonSchema result = JsonSchemaUtil.toLangChainSchema("EnumTest", json);

        // Assert
        JsonObjectSchema root = (JsonObjectSchema) result.rootElement();
        JsonStringSchema statusSchema = (JsonStringSchema) root.properties().get("status");

        assertThat(statusSchema.enumValues()).containsExactly("OPEN", "CLOSED", "PENDING");
    }

    @Test
    void toLangChainSchema_shouldThrowException_whenJsonIsInvalid() {
        // Arrange
        String invalidJson = "{ \"type\": \"object\", ... INVALID SYNTAX ... }";

        // Act & Assert
        assertThatThrownBy(() -> JsonSchemaUtil.toLangChainSchema("FailTest", invalidJson))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to parse JSON Schema string");
    }

    // --- Tests for toSchema (Vertex AI) ---

    @Test
    void toSchema_shouldReturnNull_whenInputIsEmpty() {
        // Act
        com.google.cloud.vertexai.api.Schema result = JsonSchemaUtil.toSchema("");

        // Assert
        assertThat(result).isNull();
    }

    @Test
    void toSchema_shouldReturnNull_whenInputIsNull() {
        // Act
        com.google.cloud.vertexai.api.Schema result = JsonSchemaUtil.toSchema(null);

        // Assert
        assertThat(result).isNull();
    }

    @Test
    void toSchema_shouldThrowException_whenInputIsInvalidProtobuf() {
        // The method uses Schema.parseFrom(bytes) which expects a Protobuf format.
        // Passing a standard JSON string usually fails this check or causes a parse error.

        String plainJson = "{\"some\": \"json\"}";

        // Act & Assert
        assertThatThrownBy(() -> JsonSchemaUtil.toSchema(plainJson))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to parse response schema");
    }
}