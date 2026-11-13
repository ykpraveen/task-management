package com.example.tasks.operations;

import java.util.List;
import java.util.Map;

/** A contract-defined failure rendered as application/problem+json. */
public class ApiException extends RuntimeException {

    private final ProblemCode code;
    private final List<FieldError> fieldErrors;
    private final Map<String, Object> extensions;

    public ApiException(ProblemCode code, String detail) {
        this(code, detail, List.of(), Map.of());
    }

    public ApiException(ProblemCode code, String detail, List<FieldError> fieldErrors, Map<String, Object> extensions) {
        super(detail);
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
        this.extensions = Map.copyOf(extensions);
    }

    public static ApiException notFound() {
        // Unknown and inaccessible resources are indistinguishable.
        return new ApiException(ProblemCode.NOT_FOUND, "The requested resource does not exist or is not accessible.");
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(ProblemCode.FORBIDDEN, detail);
    }

    public static ApiException validation(String field, String errorCode, String message) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, message, List.of(new FieldError(field, errorCode, message)), Map.of());
    }

    public static ApiException validation(List<FieldError> errors) {
        return new ApiException(ProblemCode.VALIDATION_FAILED, "One or more fields are invalid.", errors, Map.of());
    }

    public ProblemCode code() {
        return code;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }

    public Map<String, Object> extensions() {
        return extensions;
    }

    /** Allowlisted DTO field path, stable error code and human-readable message. */
    public record FieldError(String field, String code, String message) {
    }
}
