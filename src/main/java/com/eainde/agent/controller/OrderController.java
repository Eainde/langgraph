package com.eainde.agent.controller;

import com.eainde.agent.state.OrderState;
import com.eainde.agent.workflow.WorkflowEngine;
import lombok.RequiredArgsConstructor;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final CompiledGraph<OrderState> orderWorkflow;
    private final WorkflowEngine engine;

    public OrderController(CompiledGraph<OrderState> orderWorkflow, WorkflowEngine engine) {
        this.orderWorkflow = orderWorkflow;
        this.engine = engine;
    }

    @PostMapping("/submit")
    public Map<String, Object> submitOrder() throws ExecutionException, InterruptedException {
        String orderId = UUID.randomUUID().toString();

        // --- FIX: Use .threadId() instead of .configurable() ---
        RunnableConfig config = RunnableConfig.builder()
                .threadId(orderId) // <--- Correct Method
                .build();

        Map<String, Object> input = Map.of("orderId", orderId, "retryCount", 0);

        // Start Workflow
        OrderState result = orderWorkflow.invoke(input, config).get();

        return Map.of(
                "workflowId", orderId,
                "finalStatus", result.getStatus(),
                "retries", result.getRetryCount()
        );
    }

    @GetMapping("/{workflowId}")
    public Map<String, Object> getOrderStatus(@PathVariable String workflowId) throws Exception {

        // --- FIX: Use .threadId() here as well ---
        RunnableConfig config = RunnableConfig.builder()
                .threadId(workflowId)
                .build();

        // Fetch state
        Optional<OrderState> state = Optional.ofNullable(orderWorkflow.getState(config).state());

        if (state.isPresent()) {
            return Map.of(
                    "status", state.get().getStatus(),
                    "history", "Found in checkpoint"
            );
        } else {
            return Map.of("error", "Workflow not found");
        }
    }

    @PostMapping("/start-order")
    public String startOrder(@RequestBody Map<String, Object> data) {
        // The name matches the @Bean name inside your class
        engine.start("orderWorkflow", data);
        return "Order started!";
    }
}