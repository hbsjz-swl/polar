package com.dlchm.dlc.agent;

import java.util.Locale;

/** Retry transport failures before any streamed output; never replay tool execution. */
final class ModelRequestRetry {
    private ModelRequestRetry() { }

    static boolean transientFailure(Throwable error) {
        boolean retry = false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String name = cause.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            String detail = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
            if (name.contains("authentication") || name.contains("permissiondenied")
                    || name.contains("badrequest") || name.contains("certpath")
                    || name.contains("certificate") || detail.contains("pkix")
                    || detail.contains("certificate") || detail.contains("hostname")) return false;
            retry |= name.contains("openaiio") || name.contains("sockettimeout")
                    || name.contains("connectexception") || name.contains("eofexception")
                    || name.contains("ratelimit") || name.contains("internalserver")
                    || detail.contains("remote host terminated the handshake")
                    || detail.contains("connection reset") || detail.contains("http 503");
        }
        return retry;
    }
}
