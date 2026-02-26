-- Agent Execution Audit Trail
-- Stores per-agent invocation details for debugging and observability

CREATE TABLE agent_execution (
    execution_id        VARCHAR2(64)   NOT NULL,
    agent_id            VARCHAR2(255)  NOT NULL,
    memory_id           VARCHAR2(255),
    agent_name          VARCHAR2(255)  NOT NULL,
    invocation_order    NUMBER(10)     NOT NULL,
    status              VARCHAR2(20)   NOT NULL,
    input_data          CLOB,
    output_data         CLOB,
    error_message       CLOB,
    started_at          TIMESTAMP      NOT NULL,
    completed_at        TIMESTAMP,
    duration_ms         NUMBER(15),
    CONSTRAINT pk_agent_execution PRIMARY KEY (execution_id),
    CONSTRAINT chk_exec_status CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED'))
);

-- Query executions for a specific user/session
CREATE INDEX idx_agent_exec_memory ON agent_execution (agent_id, memory_id);

-- Find recent executions
CREATE INDEX idx_agent_exec_started ON agent_execution (started_at DESC);

-- Find stuck/failed executions
CREATE INDEX idx_agent_exec_status ON agent_execution (status, started_at);

-- Find executions by agent name (for analytics)
CREATE INDEX idx_agent_exec_name ON agent_execution (agent_name, status);

-- Ensure JSON validity on CLOB columns (Oracle 12c+)
-- Uncomment if you want strict JSON validation
-- ALTER TABLE agent_execution ADD CONSTRAINT chk_input_json CHECK (input_data IS JSON);
-- ALTER TABLE agent_execution ADD CONSTRAINT chk_output_json CHECK (output_data IS JSON);