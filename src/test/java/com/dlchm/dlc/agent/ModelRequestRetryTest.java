package com.dlchm.dlc.agent;

import static org.junit.jupiter.api.Assertions.*;
import javax.net.ssl.SSLHandshakeException;
import java.io.EOFException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class ModelRequestRetryTest {
    @Test void retriesTheReportedHandshakeTerminationThroughWrappedCauses() {
        var handshake = new SSLHandshakeException("Remote host terminated the handshake");
        handshake.initCause(new EOFException("SSL peer shut down incorrectly"));
        assertTrue(ModelRequestRetry.transientFailure(new CompletionException(handshake)));
    }
    @Test void permanentCertificateFailureIsNotRetried() {
        assertFalse(ModelRequestRetry.transientFailure(new CompletionException(
                new SSLHandshakeException("PKIX path building failed"))));
        assertFalse(ModelRequestRetry.transientFailure(new IllegalArgumentException("Bad API arguments")));
    }
}
