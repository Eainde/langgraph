package com.eainde.agent.thread;

import org.slf4j.MDC;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MdcAwareVirtualThreadExecutor implements Executor {

    private final Executor delegate = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public void execute(Runnable command) {
        // Capture MDC context from the calling (parent) thread
        Map<String, String> parentMdc = MDC.getCopyOfContextMap();

        delegate.execute(() -> {
            // Restore MDC in the virtual thread
            if (parentMdc != null) {
                MDC.setContextMap(parentMdc);
            }
            try {
                command.run();
            } finally {
                MDC.clear(); // Clean up to avoid leaks
            }
        });
    }
}
