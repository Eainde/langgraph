package com.eainde.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agentic.scope.AgenticScopeKey;
import dev.langchain4j.agentic.scope.AgenticScopeStore;
import dev.langchain4j.agentic.scope.DefaultAgenticScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.EmptyResultDataAccessException;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class JdbcAgenticScopeStore implements AgenticScopeStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcAgenticScopeStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean save(AgenticScopeKey key, DefaultAgenticScope agenticScope) {
        try {
            String json = objectMapper.writeValueAsString(agenticScope);
            String agentId = key.agentId();   // adjust if field names differ
            String memoryId = key.memoryId().toString();

            int rows = jdbcTemplate.update("""
                MERGE INTO agentic_scope (agent_id, memory_id, scope_data, updated_at)
                KEY (agent_id, memory_id)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """, agentId, memoryId, json);
            // For PostgreSQL use ON CONFLICT instead of MERGE:
            // INSERT INTO agentic_scope (agent_id, memory_id, scope_data, updated_at)
            // VALUES (?, ?, ?::jsonb, CURRENT_TIMESTAMP)
            // ON CONFLICT (agent_id, memory_id)
            // DO UPDATE SET scope_data = EXCLUDED.scope_data, updated_at = CURRENT_TIMESTAMP

            return rows > 0;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize AgenticScope", e);
        }
    }

    @Override
    public Optional<DefaultAgenticScope> load(AgenticScopeKey key) {
        try {
            String json = jdbcTemplate.queryForObject(
                    "SELECT scope_data FROM agentic_scope WHERE agent_id = ? AND memory_id = ?",
                    String.class,
                    key.agentId(),
                    key.memoryId().toString()
            );
            DefaultAgenticScope scope = objectMapper.readValue(json, DefaultAgenticScope.class);
            return Optional.of(scope);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize AgenticScope", e);
        }
    }

    @Override
    public boolean delete(AgenticScopeKey key) {
        int rows = jdbcTemplate.update(
                "DELETE FROM agentic_scope WHERE agent_id = ? AND memory_id = ?",
                key.agentId(),
                key.memoryId().toString()
        );
        return rows > 0;
    }

    @Override
    public Set<AgenticScopeKey> getAllKeys() {
        return new HashSet<>(jdbcTemplate.query(
                "SELECT agent_id, memory_id FROM agentic_scope",
                (rs, rowNum) -> new AgenticScopeKey(
                        rs.getString("agent_id"),
                        rs.getString("memory_id")
                )
        ));
    }
}
