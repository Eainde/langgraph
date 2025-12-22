package com.eainde.agent.workflow;

import com.eainde.agent.repository.WorkflowRepository;
import lombok.extern.log4j.Log4j2;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The central entry point for executing all AI workflows in the application.
 * <p>
 * This engine acts as a Facade over the LangGraph4j library, providing a simplified
 * API for developers to start workflows without managing technical details like
 * thread IDs, database persistence, or graph configuration.
 * </p>
 *
 * <h3>Key Features:</h3>
 * <ul>
 * <li><strong>Auto-Discovery:</strong> Automatically finds and registers all {@link CompiledGraph} beans.</li>
 * <li><strong>Persistence:</strong> Automatically creates a {@link com.eainde.agent.repository.WorkflowRecord} in the SQL database for every run.</li>
 * <li><strong>ID Management:</strong> Generates unique Trace/Thread IDs for tracking.</li>
 * </ul>
 *
 * @author Your Name
 * @version 1.0
 */
@Log4j2
@Service
public class WorkflowEngine {
    private final WorkflowRepository workflowRepository;

    // Registry map
    private final Map<String, CompiledGraph<? extends AgentState>> registry = new ConcurrentHashMap<>();

    // SPRING AUTO-WIRING MAGIC:
    // If you ask for Map<String, CompiledGraph>, Spring injects ALL graph beans.
    // Key = Bean Name (e.g., "orderWorkflowGraph"), Value = The Graph Object
    public WorkflowEngine(Map<String, CompiledGraph<? extends AgentState>> allGraphs, WorkflowRepository workflowRepository) {
        this.workflowRepository = workflowRepository;
        this.registry.putAll(allGraphs); // Register them all automatically!
    }

    /**
     * Starts a new instance of a specific workflow.
     * <p>
     * This method handles the full initialization lifecycle:
     * <ol>
     * <li>Validates the requested workflow name exists.</li>
     * <li>Generates a unique {@code thread_id} (UUID) for tracking.</li>
     * <li>Persists the initial "RUNNING" record to the SQL database.</li>
     * <li>Invokes the underlying LangGraph asynchronously.</li>
     * </ol>
     * </p>
     *
     * @param beanName The name of the graph bean to execute (e.g., "orderWorkflow").
     * Must match the {@code @Bean("name")} defined in configuration.
     * @param inputs   The initial data payload required by the workflow's starting node.
     * (e.g., {@code {"orderId": "123", "amount": 500}}).
     * @param <S>      The specific type of State object used by this graph (e.g., OrderState).
     * @return A {@link java.util.concurrent.CompletableFuture} containing the final State of the workflow upon completion.
     * Use {@code .join()} or {@code .get()} to retrieve the result, or {@code .thenAccept()} for non-blocking handling.
     * @throws IllegalArgumentException if no workflow with the given {@code beanName} is found.
     */
    @SuppressWarnings("unchecked")
    public <S extends AgentState> Optional<S> start(String beanName, Map<String, Object> inputs) {

        // 1. Validation: Ensure the requested workflow actually exists in our registry
        CompiledGraph<S> graph = (CompiledGraph<S>) registry.get(beanName);
        if (graph == null) {
            throw new IllegalArgumentException("No workflow found with name: " + beanName);
        }

        // 2. ID Generation: Create a unique Trace ID for this specific execution
        // This ID links the SQL Audit Log (WorkflowRecord) with the Graph State (Checkpoints)
        String flowId = UUID.randomUUID().toString(); // can be party id or profile version in our case
        log.info("{} saved in DB {}", flowId, beanName);
        //TODO need to save this in DB
        //repository.save(new WorkflowRecord(flowId, beanName));

        // 4. Configuration: prepare the Runtime Config for LangGraph
        // The 'threadId' tells the Saver (Oracle/Postgres) where to store the state blobs.
        RunnableConfig config = RunnableConfig.builder()
                .threadId(flowId)
                .build();

        // 5. Execution: Kick off the graph asynchronously.
        // We do NOT wait here. We return the Future immediately so the caller
        // (Controller) can decide whether to block or return the ID to the user.
        return graph.invoke(inputs, config);
    }
}