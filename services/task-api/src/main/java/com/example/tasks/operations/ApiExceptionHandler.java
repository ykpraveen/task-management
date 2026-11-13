package com.example.tasks.operations;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every failure to application/problem+json. Never returns stack traces, SQL or task
 * text; unexpected failures are logged with the request ID and exception type only.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail api(ApiException e) {
        return ProblemResponses.problem(e);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        List<ApiException.FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiException.FieldError(f.getField(), "INVALID", f.getDefaultMessage() == null ? "Invalid value." : f.getDefaultMessage()))
                .toList();
        return ProblemResponses.problem(ApiException.validation(errors));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ProblemDetail unreadable(Exception e) {
        return ProblemResponses.problem(ProblemCode.VALIDATION_FAILED, "The request is malformed or has invalid values.");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail missingHeader(MissingRequestHeaderException e) {
        if ("If-Match".equalsIgnoreCase(e.getHeaderName())) {
            return ProblemResponses.problem(ProblemCode.PRECONDITION_REQUIRED, "If-Match with the current task version is required.");
        }
        return ProblemResponses.problem(ApiException.validation(e.getHeaderName(), "REQUIRED", "The " + e.getHeaderName() + " header is required."));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge(MaxUploadSizeExceededException e) {
        return ProblemResponses.problem(ProblemCode.PAYLOAD_TOO_LARGE, "Files may be at most 10,000,000 bytes.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ProblemDetail mediaType(HttpMediaTypeNotSupportedException e) {
        return ProblemResponses.problem(ProblemCode.UNSUPPORTED_CONTENT, "The request content type is not supported.");
    }

    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    ProblemDetail notFound(Exception e) {
        return ProblemResponses.problem(ApiException.notFound());
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class})
    ProblemDetail dependency(Exception e) {
        log.warn("dependency unavailable: {}", e.getClass().getSimpleName());
        return ProblemResponses.problem(ProblemCode.DEPENDENCY_UNAVAILABLE, "A required service is temporarily unavailable. Try again.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("unexpected failure: {}", e.getClass().getName());
        return ProblemResponses.problem(ProblemCode.INTERNAL_ERROR, "An unexpected error occurred.");
    }
}
