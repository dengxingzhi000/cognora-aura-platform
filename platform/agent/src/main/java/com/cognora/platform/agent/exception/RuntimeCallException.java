package com.cognora.platform.agent.exception;

import java.time.Duration;

public class RuntimeCallException extends RuntimeException {

    private final Duration retryAfter;
    private final boolean statusUnknown;

    public RuntimeCallException(String message, Duration retryAfter, Throwable cause) {
        this(message, retryAfter, cause, false);
    }

    public RuntimeCallException(String message, Duration retryAfter, Throwable cause, boolean statusUnknown) {
        super(message, cause);
        this.retryAfter = retryAfter;
        this.statusUnknown = statusUnknown;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }

    public boolean isStatusUnknown() {
        return statusUnknown;
    }
}
