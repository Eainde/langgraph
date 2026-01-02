package com.eainde.agent;

import com.yourcompany.ai.model.ChatPromptConfig;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import org.springframework.stereotype.Component;

@Component
public class ChatParameterMapper {

    public ChatRequestParameters toRequestParameters(ChatPromptConfig config) {
        if (config == null) {
            return ChatRequestParameters.builder().build();
        }

        ChatRequestParameters.Builder builder = ChatRequestParameters.builder();

        // 1. Strings & Integers
        if (config.getModelName() != null) {
            builder.modelName(config.getModelName());
        }
        if (config.getMaxOutputTokens() != null) {
            builder.maxOutputTokens(config.getMaxOutputTokens());
        }

        // 2. Doubles (Temperature / TopP)
        if (config.getTemperature() != null) {
            builder.temperature(config.getTemperature());
        }
        if (config.getTopP() != null) {
            builder.topP(config.getTopP());
        }

        // 3. Integer (TopK)
        if (config.getTopK() != null) {
            builder.topK(config.getTopK());
        }

        // 4. JSON Mode Logic
        if (config.getResponseSchema() != null && !config.getResponseSchema().isEmpty()) {
            builder.responseFormat(ResponseFormat.builder()
                    .type(ResponseFormatType.JSON)
                    .build());
        }

        return builder.build();
    }
}
