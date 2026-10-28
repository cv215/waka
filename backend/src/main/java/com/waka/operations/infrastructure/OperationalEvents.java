package com.waka.operations.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Seul journal applicatif console autorise ; aucun Throwable ni objet metier. */
public final class OperationalEvents {
    private static final Logger LOG = LoggerFactory.getLogger("com.waka.operational");

    private OperationalEvents() { }

    public static void httpCompleted(String requestId, String method, int status, long durationMs) {
        LOG.info("HTTP_COMPLETED requestId={} method={} status={} durationMs={}",
                requestId, method, status, durationMs);
    }

    public static void responseIncomplete(String requestId) {
        LOG.info("HTTP_RESPONSE_INCOMPLETE requestId={}", requestId);
    }

    public static void ready() {
        LOG.info("APPLICATION_READY");
    }

    public static void startupFailed(Throwable failure) {
        LOG.info("APPLICATION_STARTUP_REJECTED code={}", LocalEnvironmentInitializer.safeFailureCode(failure));
    }
}
