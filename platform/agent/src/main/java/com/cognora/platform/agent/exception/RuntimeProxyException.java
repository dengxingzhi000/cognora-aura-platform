package com.cognora.platform.agent.exception;

import org.springframework.http.HttpStatus;

public class RuntimeProxyException extends RuntimeException {

    private final HttpStatus status;

    public RuntimeProxyException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
