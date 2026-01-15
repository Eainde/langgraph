package com.eainde.agent;

import com.google.genai.types.*;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class GoogleGenAiToolMapper {

    public static Tool convertToGoogleTool(ToolSpecification spec) {
        // 1. Convert the parameters (JsonSchema) to Google's Schema type
        Schema parameterSchema = convertToGoogleSchema(spec.parameters());

        // 2. Create the FunctionDeclaration
        FunctionDeclaration functionDeclaration = FunctionDeclaration.builder()
                .name(spec.name())
                .description(spec.description())
                .parameters(parameterSchema)
                .build();

        // 3. Wrap in a Tool object
        return Tool.builder()
                .functionDeclarations(List.of(functionDeclaration))
                .build();
    }

    private static Schema convertToGoogleSchema(JsonSchemaElement element) {
        if (element == null) {
            return null;
        }

        // --- OBJECT ---
        if (element instanceof JsonObjectSchema) {
            JsonObjectSchema objectSchema = (JsonObjectSchema) element;
            Map<String, Schema> properties = new HashMap<>();

            // Recursively convert properties
            if (objectSchema.properties() != null) {
                objectSchema.properties().forEach((key, value) ->
                        properties.put(key, convertToGoogleSchema(value))
                );
            }

            return Schema.builder()
                    .type(Type.OBJECT)
                    .properties(properties)
                    .required(objectSchema.required()) // CRITICAL: This was likely missing or empty
                    .description(objectSchema.description())
                    .build();
        }

        // --- STRING / ENUM ---
        if (element instanceof JsonStringSchema) {
            JsonStringSchema stringSchema = (JsonStringSchema) element;
            return Schema.builder()
                    .type(Type.STRING)
                    .description(stringSchema.description())
                    .build();
        }

        if (element instanceof JsonEnumSchema) {
            JsonEnumSchema enumSchema = (JsonEnumSchema) element;
            return Schema.builder()
                    .type(Type.STRING)
                    .format("enum")
                    .enum_(enumSchema.enumValues()) // Set allowed values
                    .description(enumSchema.description())
                    .build();
        }

        // --- INTEGER ---
        if (element instanceof JsonIntegerSchema) {
            return Schema.builder()
                    .type(Type.INTEGER)
                    .description(((JsonIntegerSchema) element).description())
                    .build();
        }

        // --- NUMBER ---
        if (element instanceof JsonNumberSchema) {
            return Schema.builder()
                    .type(Type.NUMBER)
                    .description(((JsonNumberSchema) element).description())
                    .build();
        }

        // --- BOOLEAN ---
        if (element instanceof JsonBooleanSchema) {
            return Schema.builder()
                    .type(Type.BOOLEAN)
                    .description(((JsonBooleanSchema) element).description())
                    .build();
        }

        // --- ARRAY ---
        if (element instanceof JsonArraySchema) {
            JsonArraySchema arraySchema = (JsonArraySchema) element;
            return Schema.builder()
                    .type(Type.ARRAY)
                    .items(convertToGoogleSchema(arraySchema.items())) // Recursion for items
                    .description(arraySchema.description())
                    .build();
        }

        throw new IllegalArgumentException("Unknown schema type: " + element.getClass());
    }
}
