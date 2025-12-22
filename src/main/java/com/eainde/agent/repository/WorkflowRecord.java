package com.eainde.agent.repository;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "WORKFLOW_DIRECTORY")
public class WorkflowRecord {
    @Id
    private String workflowId;   // The UUID
    private String workflowName; // e.g., "ORDER_FLOW"
    private String status;       // "RUNNING", "COMPLETED", "FAILED"
    private LocalDateTime createdAt;

    public WorkflowRecord() {}
    public WorkflowRecord(String id, String name) {
        this.workflowId = id;
        this.workflowName = name;
        this.status = "RUNNING";
        this.createdAt = LocalDateTime.now();
    }
}
