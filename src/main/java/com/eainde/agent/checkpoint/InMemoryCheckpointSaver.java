package com.eainde.agent.checkpoint;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryCheckpointSaver implements BaseCheckpointSaver {

    private final Map<String, Map<String, Checkpoint>> storage = new ConcurrentHashMap<>();

    @Override
    public Collection<Checkpoint> list(RunnableConfig runnableConfig) {
        return List.of();
    }

    @Override
    public Optional<Checkpoint> get(RunnableConfig config) { // <--- Return type must be Optional<Checkpoint>

        // 1. Get Thread ID safely
        String threadId = config.threadId().orElse(null);

        if (threadId == null || !storage.containsKey(threadId)) {
            return Optional.empty(); // <--- Return empty if not found
        }

        Map<String, Checkpoint> threadCheckpoints = storage.get(threadId);
        if (threadCheckpoints.isEmpty()) {
            return Optional.empty();
        }

        // 2. Logic to get the correct checkpoint
        // (Simplified: getting the "last" one added, assuming typical map iteration)
        Checkpoint latest = threadCheckpoints.values().iterator().next();

        return Optional.of(latest); // <--- Wrap result in Optional
    }

    @Override
    public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) {
        String threadId = config.threadId().orElseThrow(() ->
                new IllegalArgumentException("Thread ID is required")
        );

        storage.computeIfAbsent(threadId, k -> new ConcurrentHashMap<>());
        storage.get(threadId).put(checkpoint.getId(), checkpoint);

        return RunnableConfig.builder()
                .threadId(threadId)
                .checkPointId(checkpoint.getId())
                .build();
    }

    @Override
    public Tag release(RunnableConfig runnableConfig) throws Exception {
        return null;
    }
}