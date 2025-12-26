-- 1. Create the Thread Table (Stores workflow IDs)
CREATE TABLE LANGRAPH4J_THREAD (
thread_id VARCHAR2(36) PRIMARY KEY,
thread_name VARCHAR2(255),
is_released NUMBER(1) DEFAULT 0 NOT NULL, -- Oracle boolean workaround (0=False, 1=True)
CONSTRAINT check_bool_released CHECK (is_released IN (0, 1))
);

-- 2. Create Index on Thread Table
CREATE INDEX IDX_LG_THREAD_NAME_REL ON LANGRAPH4J_THREAD(thread_name, is_released);

-- 3. Create the Checkpoint Table (Stores the actual state)
CREATE TABLE LANGRAPH4J_CHECKPOINT (
checkpoint_id VARCHAR2(36) PRIMARY KEY,
thread_id VARCHAR2(36) NOT NULL,
node_id VARCHAR2(255),
next_node_id VARCHAR2(255),
state_data JSON NOT NULL, -- Requires Oracle 19c+ (or 21c+ for native type)
saved_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

CONSTRAINT LANGRAPH4J_FK_THREAD
FOREIGN KEY(thread_id)
REFERENCES LANGRAPH4J_THREAD(thread_id)
ON DELETE CASCADE
);


curl -N -X POST http://localhost:8080/api/chat/stream \
-H "Content-Type: text/plain" \
-d "Write a short poem about clouds."