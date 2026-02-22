package com.eainde.agent.thread;

import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.ContextSnapshot;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class ObservabilityAwareVirtualThreadExecutor implements Executor {

    private final Executor delegate = Executors.newVirtualThreadPerTaskExecutor();
    private final ContextSnapshotFactory snapshotFactory = ContextSnapshotFactory.builder().build();

    @Override
    public void execute(Runnable command) {
        // Capture BOTH MDC + Observation/Trace context
        ContextSnapshot snapshot = snapshotFactory.captureAll();

        delegate.execute(() -> {
            try (ContextSnapshot.Scope scope = snapshot.setThreadLocals()) {
                command.run();
            }
        });
    }
}
