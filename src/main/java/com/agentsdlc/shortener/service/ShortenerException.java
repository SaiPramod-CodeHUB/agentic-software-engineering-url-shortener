package com.agentsdlc.shortener.service;

import org.springframework.http.HttpStatus;

/**
 * A domain failure that maps one-to-one onto an HTTP status. Services throw
 * it; {@code ApiExceptionHandler} renders it. Keeping the status on the
 * exception avoids a parallel exception hierarchy that must be kept in sync
 * with a mapping table.
 */
public class ShortenerException extends RuntimeException {

    /** HTTP status the failure maps to. */
    private final HttpStatus status;
    /** Stable, machine-readable error code. */
    private final String errorCode;

    /**
     * Creates the exception.
     *
     * @param status    HTTP status to return
     * @param errorCode stable, machine-readable error code
     * @param message   human-readable explanation (never contains client data)
     */
    public ShortenerException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    /**
     * Returns the HTTP status.
     *
     * @return the HTTP status
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * Returns the machine-readable error code.
     *
     * @return the error code
     */
    public String errorCode() {
        return errorCode;
    }

    /**
     * 400: the submitted URL is malformed or targets a forbidden network.
     *
     * @param reason why the URL was rejected
     * @return the exception
     */
    public static ShortenerException badRequest(String reason) {
        return new ShortenerException(HttpStatus.BAD_REQUEST, "invalid_request", reason);
    }

    /**
     * 404: the code does not exist.
     *
     * @return the exception
     */
    public static ShortenerException notFound() {
        return new ShortenerException(HttpStatus.NOT_FOUND, "not_found", "unknown short code");
    }

    /**
     * 410: the link existed but has expired.
     *
     * @return the exception
     */
    public static ShortenerException gone() {
        return new ShortenerException(HttpStatus.GONE, "expired", "link has expired");
    }

    /**
     * 409: the requested custom alias is already taken.
     *
     * @return the exception
     */
    public static ShortenerException aliasTaken() {
        return new ShortenerException(HttpStatus.CONFLICT, "alias_taken", "custom alias already in use");
    }

    /**
     * 422: an idempotency key was reused with a different request body.
     *
     * @return the exception
     */
    public static ShortenerException idempotencyMismatch() {
        return new ShortenerException(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency_key_reused",
                "Idempotency-Key was already used with a different request");
    }
}
