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
import org.bsc.langgraph4j.checkpoint.OracleSaver;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

@Component
public class OrderWorkflowGraph {
    private final ValidateOrderNode validateNode;
    private final PaymentNode paymentNode;
    private final ManualReviewNode reviewNode;
    private final PaymentRoutingEdge routingEdge;
    //private final OracleSaver oracleSaver;
    private final InMemoryCheckpointSaver inMemoryCheckpointSaver;

    // 3. Constructor Injection (Autowiring is automatic here)
    public OrderWorkflowGraph(
            ValidateOrderNode validateNode,
            PaymentNode paymentNode,
            ManualReviewNode reviewNode,
            PaymentRoutingEdge routingEdge,
            InMemoryCheckpointSaver inMemoryCheckpointSaver) {
        this.validateNode = validateNode;
        this.paymentNode = paymentNode;
        this.reviewNode = reviewNode;
        this.routingEdge = routingEdge;
        this.inMemoryCheckpointSaver = inMemoryCheckpointSaver;
    }

    // 4. The Builder Method
    // This creates the actual 'CompiledGraph' bean that the Engine will pick up.
    // We give it a specific name "orderWorkflow" to reference it later.
    @Bean("orderWorkflow")
    public CompiledGraph<OrderState> build() throws GraphStateException {

        StateGraph<OrderState> workflow = new StateGraph<>(OrderState::new);

        // Use the class variables directly
        workflow.addNode("validate", validateNode);
        workflow.addNode("payment", paymentNode);
        workflow.addNode("manual_review", reviewNode);

        workflow.addEdge(START, "validate");
        workflow.addEdge("validate", "payment");

        workflow.addConditionalEdges(
                "payment",
                routingEdge,
                Map.of(
                        "success", END,
                        "retry", "payment",
                        "fallback", "manual_review"
                )
        );

        workflow.addEdge("manual_review", END);

        return workflow.compile(
                CompileConfig.builder()
                        .checkpointSaver(inMemoryCheckpointSaver)
                        .build()
        );
    }
}
