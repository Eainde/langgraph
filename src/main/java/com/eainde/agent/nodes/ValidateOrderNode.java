package com.eainde.agent.nodes;

import com.eainde.agent.state.OrderState;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class ValidateOrderNode implements AsyncNodeAction<OrderState> {
    @Override
    public CompletableFuture<Map<String, Object>> apply(OrderState state) {
        System.out.println("Processing Order: " + state.getOrderId());
        if (state.getOrderId() == null) {
            // You can also return a failed future for errors
            return CompletableFuture.failedFuture(new IllegalArgumentException("Order ID missing!"));
        }

        // Wrap the result in a generic CompletableFuture
        return CompletableFuture.completedFuture(Map.of("status", "VALIDATED"));
    }
}
