package com.eainde.agent.nodes;

import com.eainde.agent.state.OrderState;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class ManualReviewNode implements AsyncNodeAction<OrderState> {
    @Override
    public CompletableFuture<Map<String, Object>> apply(OrderState state) {
        System.out.println("--- !!! FALLBACK: SENT TO HUMAN REVIEW !!! ---");
        System.out.println("Reason: " + state.getError());

        return CompletableFuture.completedFuture(Map.of("status", "NEEDS_REVIEW"));
    }
}
