package com.dlchm.dlc.agent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.springaicommunity.agent.tools.task.repository.BackgroundTask;
import org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository;
import org.springaicommunity.agent.tools.task.repository.TaskRepository;

/** Bounded background-task storage that carries the parent turn's identity. */
public final class ContextPropagatingTaskRepository implements TaskRepository {
    private static final int MAX_BACKGROUND_TASKS = 16;

    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_BACKGROUND_TASKS, runnable -> {
        Thread thread = new Thread(runnable, "dlc-task-tool");
        thread.setDaemon(true);
        return thread;
    });
    private final DefaultTaskRepository delegate = new DefaultTaskRepository(executor);

    @Override
    public BackgroundTask getTasks(String taskId) {
        return delegate.getTasks(taskId);
    }

    @Override
    public BackgroundTask putTask(String taskId, Supplier<String> taskExecution) {
        ExecutionContext.Snapshot snapshot = ExecutionContext.capture();
        return delegate.putTask(taskId,
                () -> ExecutionContext.call(snapshot, taskExecution::get));
    }

    @Override
    public void removeTask(String taskId) {
        delegate.removeTask(taskId);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    public void shutdown() {
        delegate.clear();
        executor.shutdownNow();
    }
}
