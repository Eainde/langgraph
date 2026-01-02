package com.eainde.agent;

public interface ChatPromptConfig {
    String getModelName();
    Double getTemperature();
    Double getTopP();
    Integer getTopK();
    Integer getMaxOutputTokens();
    String getResponseSchema(); // Used to trigger JSON mode
}
