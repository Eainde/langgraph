package com.eainde.agent.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persists agent execution records to Oracle database for debugging and observability.
 */
public class OracleAgentExecutionStore {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public OracleAgentExecutionStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    /**
     * Inserts a new execution record with RUNNING status when an agent starts.
     */
    public void insertRunning(AgentExecutionRecord record) {
        String sql = """
            INSERT INTO agent_execution 
                (execution_id, agent_id, memory_id, agent_name, invocation_order, 
                 status, input_data, started_at)
            VALUES (?, ?, ?, ?, ?, 'RUNNING', ?, ?)
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, record.executionId());
            ps.setString(2, record.agentId());
            ps.setString(3, record.memoryId());
            ps.setString(4, record.agentName());
            ps.setInt(5, record.invocationOrder());
            ps.setString(6, safeSerialize(record.inputData()));
            ps.setTimestamp(7, Timestamp.from(record.startedAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert agent execution record", e);
        }
    }

    /**
     * Updates an execution record to SUCCESS status when an agent completes.
     */
    public void markSuccess(String executionId, Object output, Instant completedAt) {
        String sql = """
            UPDATE agent_execution 
            SET status = 'SUCCESS', 
                output_data = ?, 
                completed_at = ?, 
                duration_ms = ? 
            WHERE execution_id = ?
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, safeSerialize(output));
            ps.setTimestamp(2, Timestamp.from(completedAt));
            // duration_ms will be computed by the caller
            ps.setLong(3, calculateDuration(executionId, completedAt));
            ps.setString(4, executionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark agent execution as SUCCESS", e);
        }
    }

    /**
     * Updates an execution record to FAILED status when an agent throws an error.
     */
    public void markFailed(String executionId, String errorMessage, Instant completedAt) {
        String sql = """
            UPDATE agent_execution 
            SET status = 'FAILED', 
                error_message = ?, 
                completed_at = ?, 
                duration_ms = ? 
            WHERE execution_id = ?
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, errorMessage);
            ps.setTimestamp(2, Timestamp.from(completedAt));
            ps.setLong(3, calculateDuration(executionId, completedAt));
            ps.setString(4, executionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mark agent execution as FAILED", e);
        }
    }

    /**
     * Retrieves all execution records for a given agent and memory (user session).
     */
    public List<AgentExecutionRecord> findByMemoryId(String agentId, String memoryId) {
        String sql = """
            SELECT execution_id, agent_id, memory_id, agent_name, invocation_order, 
                   status, input_data, output_data, error_message, started_at, completed_at, duration_ms
            FROM agent_execution 
            WHERE agent_id = ? AND memory_id = ?
            ORDER BY invocation_order
            """;

        List<AgentExecutionRecord> records = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, agentId);
            ps.setString(2, memoryId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(mapRow(rs));
                }
            }
            return records;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query agent executions", e);
        }
    }

    /**
     * Finds stuck executions that have been RUNNING longer than the given threshold.
     */
    public List<AgentExecutionRecord> findStuckExecutions(Duration threshold) {
        String sql = """
            SELECT execution_id, agent_id, memory_id, agent_name, invocation_order,
                   status, input_data, output_data, error_message, started_at, completed_at, duration_ms
            FROM agent_execution 
            WHERE status = 'RUNNING' AND started_at < ?
            ORDER BY started_at
            """;

        List<AgentExecutionRecord> records = new ArrayList<>();
        Instant cutoff = Instant.now().minus(threshold);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setTimestamp(1, Timestamp.from(cutoff));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(mapRow(rs));
                }
            }
            return records;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query stuck executions", e);
        }
    }

    private long calculateDuration(String executionId, Instant completedAt) {
        String sql = "SELECT started_at FROM agent_execution WHERE execution_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, executionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Instant startedAt = rs.getTimestamp("started_at").toInstant();
                    return Duration.between(startedAt, completedAt).toMillis();
                }
            }
            return 0;
        } catch (SQLException e) {
            return 0; // non-critical, don't fail the update
        }
    }

    private AgentExecutionRecord mapRow(ResultSet rs) throws SQLException {
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new AgentExecutionRecord(
                rs.getString("execution_id"),
                rs.getString("agent_id"),
                rs.getString("memory_id"),
                rs.getString("agent_name"),
                rs.getInt("invocation_order"),
                rs.getString("status"),
                rs.getString("input_data"),
                rs.getString("output_data"),
                rs.getString("error_message"),
                rs.getTimestamp("started_at").toInstant(),
                completedAt != null ? completedAt.toInstant() : null,
                rs.getLong("duration_ms")
        );
    }

    private String safeSerialize(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return value.toString(); // fallback to toString rather than failing
        }
    }
}
