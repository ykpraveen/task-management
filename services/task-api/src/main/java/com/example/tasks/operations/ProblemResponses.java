package com.example.tasks.operations;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/** Builds contract problem documents: type,title,status,code,detail,requestId[,fieldErrors]. */
public final class ProblemResponses {

    public static final String REQUEST_ID = "requestId";

    private ProblemResponses() {
    }

    public static ProblemDetail problem(ApiException e) {
        ProblemDetail problem = problem(e.code(), e.getMessage());
        if (!e.fieldErrors().isEmpty()) {
            problem.setProperty("fieldErrors", e.fieldErrors());
        }
        e.extensions().forEach(problem::setProperty);
        return problem;
    }

    public static ProblemDetail problem(ProblemCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create("https://tasks.demo.invalid/problems/" + code.name().toLowerCase().replace('_', '-')));
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());
        problem.setProperty(REQUEST_ID, MDC.get(REQUEST_ID));
        return problem;
    }

    /** Writes a problem from servlet filters, outside Spring MVC. */
    public static void write(HttpServletResponse response, ProblemCode code, String detail) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String requestId = MDC.get(REQUEST_ID);
        String body = "{\"type\":" + json("https://tasks.demo.invalid/problems/" + code.name().toLowerCase().replace('_', '-'))
                + ",\"title\":" + json(code.title())
                + ",\"status\":" + code.status().value()
                + ",\"code\":" + json(code.name())
                + ",\"detail\":" + json(detail)
                + ",\"requestId\":" + (requestId == null ? "null" : json(requestId)) + "}";
        response.getWriter().write(body);
    }

    static String json(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

}
