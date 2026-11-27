package com.cognora.platform.agent.exception;

import java.time.Duration;

import com.cognora.platform.common.response.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AgentExceptionHandler {

    @ExceptionHandler(RuntimeProxyException.class)
    public ResponseEntity<ApiResponse<Object>> handleRuntimeRejection(RuntimeProxyException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(ApiResponse.fail(exception.getStatus().value(), exception.getMessage()));
    }

    @ExceptionHandler(RuntimeCallException.class)
    public ResponseEntity<ApiResponse<Void>> handleRuntimeFailure(RuntimeCallException exception) {
        Duration retryAfter = exception.getRetryAfter() == null ? Duration.ofSeconds(2) : exception.getRetryAfter();
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, retryAfter.toSeconds())))
                .body(ApiResponse.fail(HttpStatus.BAD_GATEWAY.value(), "Agent runtime is temporarily unavailable"));
    }
}
