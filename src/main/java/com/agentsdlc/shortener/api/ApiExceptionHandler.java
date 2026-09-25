package com.agentsdlc.shortener.api;

import com.agentsdlc.shortener.service.RateLimitedException;
import com.agentsdlc.shortener.service.ShortenerException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps failures to the uniform {@link ErrorResponse} body. Messages are
 * fixed strings chosen by the service, never echoes of client input, so error
 * responses cannot be used to reflect content or leak internals.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** Creates the handler; instantiated by Spring. */
    public ApiExceptionHandler() {
        // Stateless advice bean.
    }

    /**
     * Renders domain failures, adding {@code Retry-After} for 429.
     *
     * @param e the failure
     * @return the error response
     */
    @ExceptionHandler(ShortenerException.class)
    public ResponseEntity<ErrorResponse> handleDomain(ShortenerException e) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.status());
        if (e instanceof RateLimitedException limited) {
            builder.header(HttpHeaders.RETRY_AFTER, Long.toString(limited.retryAfterSeconds()));
        }
        return builder.body(new ErrorResponse(e.errorCode(), e.getMessage()));
    }

    /**
     * Renders bean-validation and malformed-JSON failures as 400.
     *
     * @param e the failure
     * @return the error response
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorResponse> handleBadInput(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("invalid_request", "request body is missing or invalid"));
    }
}
