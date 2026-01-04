package com.eainde.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class JsonSchemaConverter {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static JsonSchema toLangChainSchema(String name, String jsonSchemaString) {
        try {
            JsonNode rootNode = objectMapper.readTree(jsonSchemaString);
            return JsonSchema.builder()
                    .name(name != null ? name : "Schema")
                    .rootElement(parseElement(rootNode))
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON Schema string", e);
        }
    }

    private static JsonSchemaElement parseElement(JsonNode node) {
        if (!node.has("type")) {
            // Fallback or assume object if properties exist
            if (node.has("properties")) return parseObject(node);
            return JsonStringSchema.builder().build();
        }

        String type = node.get("type").asText();

        return switch (type) {
            case "object" -> parseObject(node);
            case "array" -> parseArray(node);
            case "string" -> parseString(node);
            case "integer" -> parseInteger(node);
            case "number" -> parseNumber(node);
            case "boolean" -> parseBoolean(node);
            default -> JsonStringSchema.builder().build(); // Fallback
        };
    }

    private static JsonObjectSchema parseObject(JsonNode node) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();

        if (node.has("description")) {
            builder.description(node.get("description").asText());
        }

        if (node.has("properties")) {
            JsonNode props = node.get("properties");
            Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                builder.addProperty(field.getKey(), parseElement(field.getValue()));
            }
        }

        if (node.has("required") && node.get("required").isArray()) {
            List<String> requiredFields = new ArrayList<>();
            node.get("required").forEach(n -> requiredFields.add(n.asText()));
            builder.required(requiredFields);
        }

        return builder.build();
    }

    private static JsonArraySchema parseArray(JsonNode node) {
        JsonArraySchema.Builder builder = JsonArraySchema.builder();
        if (node.has("description")) builder.description(node.get("description").asText());
        if (node.has("items")) {
            builder.items(parseElement(node.get("items")));
        }
        return builder.build();
    }

    private static JsonSchemaElement parseString(JsonNode node) {
        // Handle Enums
        if (node.has("enum")) {
            List<String> enumValues = new ArrayList<>();
            node.get("enum").forEach(n -> enumValues.add(n.asText()));
            return JsonEnumSchema.builder()
                    .description(node.has("description") ? node.get("description").asText() : null)
                    .enumValues(enumValues)
                    .build();
        }

        return JsonStringSchema.builder()
                .description(node.has("description") ? node.get("description").asText() : null)
                .build();
    }

    private static JsonIntegerSchema parseInteger(JsonNode node) {
        return JsonIntegerSchema.builder()
                .description(node.has("description") ? node.get("description").asText() : null)
                .build();
    }

    private static JsonNumberSchema parseNumber(JsonNode node) {
        return JsonNumberSchema.builder()
                .description(node.has("description") ? node.get("description").asText() : null)
                .build();
    }

    private static JsonBooleanSchema parseBoolean(JsonNode node) {
        return JsonBooleanSchema.builder()
                .description(node.has("description") ? node.get("description").asText() : null)
                .build();
    }
}
