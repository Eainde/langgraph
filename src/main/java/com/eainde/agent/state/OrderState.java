package com.eainde.agent.state;

import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;

public class OrderState extends AgentState{

    public OrderState(Map<String, Object> initData) {
        super(initData);
    }

    public String getOrderId() { return (String) this.data().get("orderId"); }
    public String getStatus() { return (String) this.data().get("status"); }
    public int getRetryCount() {
        return this.data().containsKey("retryCount") ? (int) this.data().get("retryCount") : 0;
    }
    public String getError() { return (String) this.data().get("error"); }

    // Helper for easier state updates
    public static Map<String, Object> updateStatus(String status) {
        return Map.of("status", status);
    }
}
