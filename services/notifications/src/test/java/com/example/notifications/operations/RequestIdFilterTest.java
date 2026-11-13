package com.example.notifications.operations;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(OutputCaptureExtension.class)
class RequestIdFilterTest {

    @Test
    void assignsAServerRequestIdAndLogsThePathWithoutQueryOrHeaders(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        request.setQueryString("view=assigned&query=private-search-term");
        request.addHeader("X-Request-Id", "client-chosen");
        request.addHeader("Authorization", "Bearer secret-token-value");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seen = new String[1];

        new RequestIdFilter().doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen[0] = MDC.get(ProblemResponses.REQUEST_ID);
            }
        });

        assertThat(response.getHeader("X-Request-Id")).isNotEqualTo("client-chosen").isEqualTo(seen[0]);
        assertThat(MDC.get(ProblemResponses.REQUEST_ID)).isNull();
        assertThat(output.getAll()).contains("GET /api/v1/tasks 200")
                .doesNotContain("private-search-term").doesNotContain("secret-token-value").doesNotContain("client-chosen");
    }

    @Test
    void escapesProblemJsonWrittenOutsideMvc() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ProblemResponses.write(response, ProblemCode.FORBIDDEN, "quote \" backslash \\ newline \n");
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString())
                .contains("\"code\":\"FORBIDDEN\"")
                .contains("quote \\\" backslash \\\\ newline \\n");
    }
}
