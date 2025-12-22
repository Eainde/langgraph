package com.eainde.agent.edges;

import com.eainde.agent.state.OrderState;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class PaymentRoutingEdge implements AsyncEdgeAction<OrderState> {

    @Override
    public CompletableFuture<String> apply(OrderState state) {

        // Logic to determine the NEXT node name
        String nextNode;

        if ("PAID".equals(state.getStatus())) {
            nextNode = "success";
        }
        else if (state.getRetryCount() < 3) {
            nextNode = "retry";
        }
        else {
            nextNode = "fallback";
        }

        // Must return a CompletableFuture for AsyncEdgeAction
        return CompletableFuture.completedFuture(nextNode);
    }
}
