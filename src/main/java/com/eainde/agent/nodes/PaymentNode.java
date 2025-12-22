package com.eainde.agent.nodes;

import com.eainde.agent.state.OrderState;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

@Component
public class PaymentNode implements AsyncNodeAction<OrderState> {
    private final Random random = new Random();

    @Override
    public CompletableFuture<Map<String, Object>> apply(OrderState state) {
        int currentRetries = state.getRetryCount();
        System.out.println("Attempting Payment. Try #" + (currentRetries + 1));

        if (random.nextDouble() < 0.7) {
            System.out.println("Payment Failed!");
            return CompletableFuture.completedFuture(Map.of(
                    "status", "PAYMENT_FAILED",
                    "retryCount", currentRetries + 1,
                    "error", "Gateway Timeout"
            ));
        }

        System.out.println("Payment Successful!");
        return CompletableFuture.completedFuture(Map.of("status", "PAID"));
    }
}
