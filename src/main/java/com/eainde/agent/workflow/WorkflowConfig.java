package com.eainde.agent.workflow;


import com.eainde.agent.checkpoint.InMemoryCheckpointSaver;
import com.eainde.agent.edges.PaymentRoutingEdge;
import com.eainde.agent.nodes.ManualReviewNode;
import com.eainde.agent.nodes.PaymentNode;
import com.eainde.agent.nodes.ValidateOrderNode;
import com.eainde.agent.state.OrderState;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

@Deprecated
@Configuration
public class WorkflowConfig {

    @Bean
    @Deprecated
    public CompiledGraph<OrderState> orderWorkfloww(
            ValidateOrderNode validateNode,
            PaymentNode paymentNode,
            ManualReviewNode reviewNode,
            PaymentRoutingEdge paymentRoutingEdge,
            InMemoryCheckpointSaver oracleSaver) throws GraphStateException { // Inject the Saver

        StateGraph<OrderState> workflow = new StateGraph<>(OrderState::new);

        // 1. Add Nodes
        workflow.addNode("validate", validateNode);
        workflow.addNode("payment", paymentNode);
        workflow.addNode("manual_review", reviewNode);

        // 2. Standard Edges
        workflow.addEdge(START, "validate");
        workflow.addEdge("validate", "payment");

        // 3. Conditional Edges (Fixed)
        // We pass the injected 'paymentRoutingEdge' bean as the second argument.
        // It satisfies the AsyncEdgeAction interface requirement.
        workflow.addConditionalEdges(
                "payment",
                paymentRoutingEdge,
                Map.of(
                        "success", END,
                        "retry", "payment",
                        "fallback", "manual_review"
                )
        );

        workflow.addEdge("manual_review", END);

        // --- COMPILE WITH SAVER ---
        return workflow.compile(
                CompileConfig.builder()
                        .checkpointSaver(oracleSaver) // <--- Attach DB Saver here
                        .build()
        );
    }
}