package com.example.notifications.operations;

import org.springframework.http.HttpStatus;

/** Stable machine-readable problem codes from contracts/http-api.md. */
public enum ProblemCode {
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, "Validation failed"),
    STALE_VERSION(HttpStatus.CONFLICT, "The task was changed by someone else"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Idempotency key reused with a different request"),
    PAST_DEADLINE_CONFIRMATION_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "The deadline is in the past"),
    PRECONDITION_REQUIRED(HttpStatus.PRECONDITION_REQUIRED, "A required precondition header is missing"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "This operation is not allowed"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "A required service is unavailable"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "The file is too large"),
    UNSUPPORTED_CONTENT(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The file type is not supported"),
    OPERATION_IN_PROGRESS(HttpStatus.CONFLICT, "The operation is still in progress"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");

    private final HttpStatus status;
    private final String title;

    ProblemCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
