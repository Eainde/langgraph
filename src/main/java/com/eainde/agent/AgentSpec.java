package com.eainde.agent;

import java.util.List;
import java.util.Map;

/**
 * Declarative specification for an agent.
 * Contains only metadata — no prompts, no model, no framework dependencies.
 *
 * Prompts are resolved by the AgentFactory from PromptService using the agentName.
 *
 * Usage:
 *   AgentSpec.of("csm-source-validator", "Validates source documents")
 *            .inputs("sourceText")
 *            .outputKey("sourceValidation");
 */
public class AgentSpec {

    private final String agentName;       // DB key for prompt lookup
    private final String description;     // Agent description
    private final List<String> inputKeys; // Variables read from AgenticScope
    private final String outputKey;       // Variable written to AgenticScope

    private AgentSpec(String agentName, String description, List<String> inputKeys, String outputKey) {
        this.agentName = agentName;
        this.description = description;
        this.inputKeys = inputKeys;
        this.outputKey = outputKey;
    }

    // --- Fluent builder ---

    public static Builder of(String agentName, String description) {
        return new Builder(agentName, description);
    }

    public String getAgentName() { return agentName; }
    public String getDescription() { return description; }
    public List<String> getInputKeys() { return inputKeys; }
    public String getOutputKey() { return outputKey; }

    @Override
    public String toString() {
        return agentName + " [" + String.join(",", inputKeys) + "] → " + outputKey;
    }

    public static class Builder {
        private final String agentName;
        private final String description;
        private List<String> inputKeys = List.of();
        private String outputKey;

        private Builder(String agentName, String description) {
            this.agentName = agentName;
            this.description = description;
        }

        /** Scope variables this agent reads. */
        public Builder inputs(String... keys) {
            this.inputKeys = List.of(keys);
            return this;
        }

        /** Scope variable this agent writes. */
        public Builder outputKey(String outputKey) {
            this.outputKey = outputKey;
            return this;
        }

        public AgentSpec build() {
            if (outputKey == null || outputKey.isBlank()) {
                throw new IllegalArgumentException("outputKey is required for agent: " + agentName);
            }
            return new AgentSpec(agentName, description, inputKeys, outputKey);
        }
    }
}
