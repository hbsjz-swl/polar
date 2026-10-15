package com.dlchm.dlc.agent;

import java.util.concurrent.Callable;
import com.dlchm.dlc.session.Session;

/** Thread-local execution metadata shared by tools during one agent turn. */
public final class ExecutionContext {
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private ExecutionContext() {}

    public static void run(Session session, int subagentDepth, Runnable action) {
        call(session, subagentDepth, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T call(Session session, int subagentDepth, Callable<T> action) {
        State previous = CURRENT.get();
        CURRENT.set(new State(session == null ? null : session.getId(), subagentDepth, false));
        return callWithPrevious(previous, action);
    }

    /** Capture request identity before handing a task to an asynchronous executor. */
    public static Snapshot capture() {
        State state = CURRENT.get();
        return state == null ? new Snapshot(null, 0, false)
                : new Snapshot(state.sessionId(), state.subagentDepth(), state.approvalBypass());
    }

    /** Run work with a previously captured request identity on another thread. */
    public static <T> T call(Snapshot snapshot, Callable<T> action) {
        State previous = CURRENT.get();
        Snapshot context = snapshot == null ? new Snapshot(null, 0, false) : snapshot;
        CURRENT.set(new State(context.sessionId(), context.subagentDepth(), context.approvalBypass()));
        return callWithPrevious(previous, action);
    }

    private static <T> T callWithPrevious(State previous, Callable<T> action) {
        try {
            return action.call();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static String sessionId() {
        State state = CURRENT.get();
        return state == null ? null : state.sessionId();
    }

    public static int subagentDepth() {
        State state = CURRENT.get();
        return state == null ? 0 : state.subagentDepth();
    }

    public static boolean approvalBypass() {
        State state = CURRENT.get();
        return state != null && state.approvalBypass();
    }

    public static <T> T withApprovalBypass(Callable<T> action) {
        State previous = CURRENT.get();
        State current = previous == null ? new State(null, 0, true)
                : new State(previous.sessionId(), previous.subagentDepth(), true);
        CURRENT.set(current);
        try {
            return action.call();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    private record State(String sessionId, int subagentDepth, boolean approvalBypass) {}

    public record Snapshot(String sessionId, int subagentDepth, boolean approvalBypass) {}
}
