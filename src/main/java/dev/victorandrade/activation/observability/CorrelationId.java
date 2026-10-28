package dev.victorandrade.activation.observability;

import org.slf4j.MDC;

/** Correlation id shared by the API response, the logs (MDC) and the provider call. */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "traceId";

    private CorrelationId() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
