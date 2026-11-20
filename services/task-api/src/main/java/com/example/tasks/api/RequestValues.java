package com.example.tasks.api;

import com.example.tasks.operations.ApiException;
import com.example.tasks.operations.ProblemCode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsing of path IDs, precondition headers, page limits and opaque cursors with the contract's
 * status codes: malformed values are 422, missing If-Match or Idempotency-Key is 428.
 */
final class RequestValues {

    static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 100;

    private static final Pattern CANONICAL_ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern IF_MATCH = Pattern.compile("\"([1-9][0-9]{0,18})\"");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private RequestValues() {
    }

    /** A canonical positive decimal ID in 1..9223372036854775807; anything else is 422. */
    static long id(String value, String field) {
        if (value != null && CANONICAL_ID.matcher(value).matches()) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                // above the bigint range
            }
        }
        throw ApiException.validation(field, "INVALID_ID", "IDs are positive decimal strings within the 64-bit range.");
    }

    /** The task version from {@code If-Match: "<version>"}. */
    static long ifMatch(String header) {
        if (header == null || header.isBlank()) {
            throw new ApiException(ProblemCode.PRECONDITION_REQUIRED, "If-Match with the current task version is required.");
        }
        Matcher matcher = IF_MATCH.matcher(header.strip());
        if (matcher.matches()) {
            try {
                return Long.parseLong(matcher.group(1));
            } catch (NumberFormatException e) {
                // above the bigint range
            }
        }
        throw new ApiException(ProblemCode.VALIDATION_FAILED, "If-Match must be the quoted task version from the ETag.");
    }

    static UUID idempotencyKey(String header) {
        if (header == null || header.isBlank()) {
            throw new ApiException(ProblemCode.PRECONDITION_REQUIRED, "An Idempotency-Key header is required.");
        }
        try {
            UUID key = UUID.fromString(header.strip());
            if (key.toString().equalsIgnoreCase(header.strip())) {
                return key;
            }
        } catch (IllegalArgumentException e) {
            // not a UUID
        }
        throw new ApiException(ProblemCode.VALIDATION_FAILED, "Idempotency-Key must be a UUID.");
    }

    static int limit(String value) {
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(value);
            if (limit >= 1 && limit <= MAX_LIMIT) {
                return limit;
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        throw ApiException.validation("limit", "OUT_OF_RANGE", "Limit must be between 1 and 100.");
    }

    /** An opaque cursor: each part base64url-encoded, joined with dots, bound to {@code scope}. */
    static String cursor(String scope, String... parts) {
        List<String> encoded = new ArrayList<>();
        encoded.add(encode(scope));
        for (String part : parts) {
            encoded.add(encode(part == null ? "" : part));
        }
        return ENCODER.encodeToString(String.join(".", encoded).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The parts of a cursor issued for {@code scope}; a malformed cursor, or one issued for other
     * search, filter or sort parameters, is 422.
     */
    static List<String> cursorParts(String cursor, String scope, int count) {
        try {
            String[] encoded = new String(DECODER.decode(cursor), StandardCharsets.UTF_8).split("\\.", -1);
            if (encoded.length == count + 1 && decode(encoded[0]).equals(scope)) {
                List<String> parts = new ArrayList<>();
                for (int i = 1; i < encoded.length; i++) {
                    parts.add(decode(encoded[i]));
                }
                return parts;
            }
        } catch (IllegalArgumentException e) {
            // fall through
        }
        throw ApiException.validation("cursor", "INVALID_CURSOR", "The page cursor is invalid. Load the first page again.");
    }

    private static String encode(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(DECODER.decode(value), StandardCharsets.UTF_8);
    }
}
