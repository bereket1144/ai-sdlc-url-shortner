package io.github.bereket1144.agentcontroller.run;

import java.net.URI;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * One error format for the whole API: RFC 9457 problem details, served as application/problem+json.
 * The base class already maps the framework's own failures (malformed JSON, missing body, unknown path,
 * unsupported content type) to problem details; this class adds the field names to a validation failure
 * and turns any other failure into a generic 500.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** The default problem type written out; Spring does not expose it, so it is named here. */
    private static final URI BLANK_TYPE = URI.create("about:blank");

    static final String TYPE_PREFIX = "urn:agent-controller:problem:";

    static final String INTERNAL_ERROR_DETAIL = "An unexpected error occurred.";

    /** One offending field and why it was refused. */
    public record FieldProblem(String field, String message) {
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldProblem> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldProblem(error.getField(), error.getDefaultMessage()))
                .toList();
        ProblemDetail problem = ex.getBody();
        problem.setDetail("Request validation failed.");
        problem.setProperty("errors", errors);
        log.warn("event=request_rejected status=400 reason=validation fields={}",
                errors.stream().map(FieldProblem::field).toList());
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /**
     * Every problem, whether built by the framework or by this class, passes through here. Spring leaves
     * "type" out of the JSON while it is the default "about:blank", but the API promises the field, so each
     * problem gets an explicit type that is stable per status. A type that was set on purpose (anything other
     * than the default) is kept.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response.getBody() instanceof ProblemDetail problem && isDefaultType(problem.getType())) {
            problem.setType(URI.create(TYPE_PREFIX + statusCode.value()));
        }
        return response;
    }

    /** Spring 7 returns null for a problem that never had a type set; "about:blank" is the same thing written out. */
    private static boolean isDefaultType(URI type) {
        return type == null || BLANK_TYPE.equals(type);
    }

    /**
     * Last resort. The real exception is logged here, once, with its stack trace; the client only gets
     * a generic message so that no class name, message, or stack trace leaves the server.
     * <p>
     * The exceptions that the base class lists (ErrorResponseException and so ResponseStatusException,
     * NoResourceFoundException, HttpMessageNotReadableException and the other web exceptions) have a more
     * specific handler, so they keep their own status and never reach this method. Not listed, and therefore
     * masked as a 500 here: exceptions annotated with @ResponseStatus, and Spring Security's
     * AccessDeniedException and AuthenticationException. They must be handled when Spring Security is added.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("event=request_failed status=500 reason=unexpected_exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                INTERNAL_ERROR_DETAIL);
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }
}
