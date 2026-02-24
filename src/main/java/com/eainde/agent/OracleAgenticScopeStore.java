package com.eainde.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agentic.scope.AgenticScopeKey;
import dev.langchain4j.agentic.scope.AgenticScopeStore;
import dev.langchain4j.agentic.scope.DefaultAgenticScope;

import javax.sql.DataSource;
import java.sql.*;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * CREATE TABLE agentic_scope (
 *     agent_id    VARCHAR2(255)  NOT NULL,
 *     memory_id   VARCHAR2(255)  NOT NULL,
 *     scope_data  CLOB           NOT NULL,
 *     created_at  TIMESTAMP      DEFAULT SYSTIMESTAMP,
 *     updated_at  TIMESTAMP      DEFAULT SYSTIMESTAMP,
 *     CONSTRAINT pk_agentic_scope PRIMARY KEY (agent_id, memory_id),
 *     CONSTRAINT chk_scope_json CHECK (scope_data IS JSON)
 * );
 *
 * -- For faster lookups by time (useful for debugging)
 * CREATE INDEX idx_agentic_scope_updated ON agentic_scope (updated_at DESC);
 *
 * -- For querying by agent
 * CREATE INDEX idx_agentic_scope_agent ON agentic_scope (agent_id);
 *
 *
 * @Bean
 *     public AgenticScopeStore agenticScopeStore(DataSource dataSource,
 *                                                 ObjectMapper objectMapper) {
 *         OracleAgenticScopeStore store = new OracleAgenticScopeStore(dataSource, objectMapper);
 *         AgenticScopePersister.setStore(store);
 *         return store;
 *     }
 */
public class OracleAgenticScopeStore implements AgenticScopeStore {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public OracleAgenticScopeStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean save(AgenticScopeKey key, DefaultAgenticScope agenticScope) {
        String json;
        try {
            json = objectMapper.writeValueAsString(agenticScope);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize AgenticScope", e);
        }

        String sql = """
            MERGE INTO agentic_scope tgt
            USING (SELECT ? AS agent_id, ? AS memory_id FROM DUAL) src
            ON (tgt.agent_id = src.agent_id AND tgt.memory_id = src.memory_id)
            WHEN MATCHED THEN
                UPDATE SET tgt.scope_data = ?, tgt.updated_at = SYSTIMESTAMP
            WHEN NOT MATCHED THEN
                INSERT (agent_id, memory_id, scope_data, created_at, updated_at)
                VALUES (src.agent_id, src.memory_id, ?, SYSTIMESTAMP, SYSTIMESTAMP)
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, key.agentId());
            ps.setString(2, key.memoryId().toString());
            ps.setString(3, json);  // WHEN MATCHED - update
            ps.setString(4, json);  // WHEN NOT MATCHED - insert
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save AgenticScope", e);
        }
    }

    @Override
    public Optional<DefaultAgenticScope> load(AgenticScopeKey key) {
        String sql = "SELECT scope_data FROM agentic_scope WHERE agent_id = ? AND memory_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, key.agentId());
            ps.setString(2, key.memoryId().toString());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Clob clob = rs.getClob("scope_data");
                    String json = clob.getSubString(1, (int) clob.length());
                    clob.free();
                    DefaultAgenticScope scope = objectMapper.readValue(json, DefaultAgenticScope.class);
                    return Optional.of(scope);
                }
                return Optional.empty();
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new RuntimeException("Failed to load AgenticScope", e);
        }
    }

    @Override
    public boolean delete(AgenticScopeKey key) {
        String sql = "DELETE FROM agentic_scope WHERE agent_id = ? AND memory_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, key.agentId());
            ps.setString(2, key.memoryId().toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete AgenticScope", e);
        }
    }

    @Override
    public Set<AgenticScopeKey> getAllKeys() {
        String sql = "SELECT agent_id, memory_id FROM agentic_scope";
        Set<AgenticScopeKey> keys = new HashSet<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                keys.add(new AgenticScopeKey(
                        rs.getString("agent_id"),
                        rs.getString("memory_id")
                ));
            }
            return keys;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get all AgenticScope keys", e);
        }
    }
}
