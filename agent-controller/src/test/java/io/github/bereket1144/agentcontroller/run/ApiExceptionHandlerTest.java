package io.github.bereket1144.agentcontroller.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

// Unit tests for the branch of ApiExceptionHandler that no HTTP request reaches: handleExceptionInternal is
// only ever called by the framework with an ErrorResponse exception or by this class with a ProblemDetail,
// so the "body is not a ProblemDetail" case can only be exercised by calling the method directly.
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest());

    @Test
    @DisplayName("a body that is not a ProblemDetail is returned untouched")
    void nonProblemBodyIsLeftAlone() {
        ResponseEntity<Object> response = handler.handleExceptionInternal(new RuntimeException("x"),
                "plain body", new HttpHeaders(), HttpStatus.BAD_REQUEST, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo("plain body");
    }

    @Test
    @DisplayName("a ProblemDetail whose type is explicitly about:blank gets the stable type for its status")
    void explicitBlankTypeIsReplaced() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(URI.create("about:blank"));

        ResponseEntity<Object> response = handler.handleExceptionInternal(new RuntimeException("x"),
                problem, new HttpHeaders(), HttpStatus.CONFLICT, request);

        assertThat(((ProblemDetail) response.getBody()).getType().toString())
                .isEqualTo(ApiExceptionHandler.TYPE_PREFIX + 409);
    }

    @Test
    @DisplayName("a ProblemDetail whose type was set on purpose keeps that type")
    void customTypeIsKept() {
        URI custom = URI.create("https://example.test/problems/out-of-budget");
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setType(custom);

        ResponseEntity<Object> response = handler.handleExceptionInternal(new RuntimeException("x"),
                problem, new HttpHeaders(), HttpStatus.CONFLICT, request);

        assertThat(response.getBody()).isInstanceOf(ProblemDetail.class);
        assertThat(((ProblemDetail) response.getBody()).getType()).isEqualTo(custom);
    }
}
