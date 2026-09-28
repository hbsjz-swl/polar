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
}
