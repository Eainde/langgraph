package com.eainde.agent;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import com.langchain4j.graph.StateGraph; // Conceptual import - adjust to your specific library version
import com.langchain4j.graph.Graph;

@Configuration
public class WorkflowGraph {

    @Bean
    public StateGraph<AgentState> csmExtractionGraph(ParallelExtractionNode extractionNode) {
        // 1. Initialize Graph with our State class
        StateGraph<AgentState> graph = new StateGraph<>(AgentState.class);

        // 2. Add the Node (The Worker)
        // "process_batch" is the name of this step in the workflow
        graph.addNode("process_batch", extractionNode::execute);

        // 3. Set Entry Point
        // When the graph starts, go immediately to processing the first batch
        graph.setEntryPoint("process_batch");

        // 4. Add the Conditional Edge (The Loop Logic)
        // This is the "While Loop" of the graph.
        graph.addConditionalEdge(
                "process_batch", // FROM: The processing node
                (state) -> {
                    // THE DECISION LOGIC:
                    // Check if the Input Queue still has files waiting.
                    if (!state.getFileQueue().isEmpty()) {
                        return "process_batch"; // TRUE: Loop back and run the node again
                    } else {
                        return END; // FALSE: No more files, exit to finish (or next Agent)
                    }
                }
        );

        return graph;
    }
}
