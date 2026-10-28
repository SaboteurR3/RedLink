package ge.tsu.redlink.missioncontrol.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.List;
import java.util.stream.Stream;

/**
 * Every error of the public API as RFC 9457 Problem Details with a stable {@code type}. Extends
 * Spring's handler, so standard MVC errors (405, 406, 415, ...) are problems too; replaces Boot's
 * default one. Never reveals stack traces, SQL or parser messages.
 */
@RestControllerAdvice
@Slf4j
public class ApiProblemHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<String> errors = Stream.concat(
                        ex.getBindingResult().getFieldErrors().stream()
                                .map(error -> error.getField() + ": " + error.getDefaultMessage()),
                        ex.getBindingResult().getGlobalErrors().stream()
                                .map(error -> error.getObjectName() + ": " + error.getDefaultMessage()))
                .sorted()
                .toList();
        return handleExceptionInternal(ex, validationProblem("The request body is invalid", errors), headers, status, request);
    }

    /** Constraint violations on path, query or header parameters (e.g. {@code size=500}). */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        List<String> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> result.getMethodParameter().getParameterName() + ": " + error.getDefaultMessage()))
                .sorted()
                .toList();
        return handleExceptionInternal(ex, validationProblem("A request parameter is invalid", errors),
                headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Unparseable JSON, or a value of the wrong kind. Names the field and, for enums and the command
     * dictionary, the allowed values, so the client can fix the request; never echoes the parser's
     * own message (which reveals class names).
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ProblemTypes.MALFORMED_REQUEST,
                "Malformed request", "The request body is not valid JSON of the expected shape");
        List<String> errors = ReadableErrors.of(ex.getMostSpecificCause());
        if (!errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /**
     * Spring's own errors (405, 406, 415, ...) arrive here with {@code type: about:blank}; give each a
     * stable type too, so clients can rely on {@code type} for every error.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        // Spring passes no body for its own exceptions; the problem is the exception's own one.
        Object problem = body == null && ex instanceof ErrorResponse errorResponse ? errorResponse.getBody() : body;
        if (problem instanceof ProblemDetail detail
                && (detail.getType() == null || "about:blank".equals(detail.getType().toString()))) {
            detail.setType(ProblemTypes.forStatus(statusCode.value()));
        }
        return super.handleExceptionInternal(ex, problem, headers, statusCode, request);
    }

    /** e.g. {@code /rovers/abc}. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String name = ex.getPropertyName() == null ? "A parameter" : "'" + ex.getPropertyName() + "'";
        return handleExceptionInternal(ex, problem(HttpStatus.BAD_REQUEST, ProblemTypes.MALFORMED_REQUEST,
                "Malformed request", name + " has the wrong type"), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex, HttpHeaders headers,
                                                                    HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(ex, problem(HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND, "Not found",
                "No such endpoint"), headers, status, request);
    }

    /** Every feature-specific error: the exception carries its status, type and title. */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException e) {
        ProblemDetail problem = problem(e.getStatus(), e.getType(), e.getTitle(), e.getMessage());
        e.properties().forEach(problem::setProperty);
        return ResponseEntity.status(e.getStatus()).headers(headers -> e.headers().forEach(headers::add)).body(problem);
    }

    /** Another writer committed between our If-Match check and our update: same answer as a stale tag. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail concurrentUpdate() {
        return problem(HttpStatus.PRECONDITION_FAILED, ProblemTypes.PRECONDITION_FAILED, "Precondition failed",
                "The resource was changed by someone else; fetch it again and retry");
    }

    /** Method security ({@code @PreAuthorize}) denials; same shape as the filter chain's 403. */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail accessDenied() {
        return problem(HttpStatus.FORBIDDEN, ProblemTypes.FORBIDDEN, "Forbidden", "Your role does not allow this action");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL_ERROR, "Internal error",
                "Something went wrong on our side");
    }

    private static ProblemDetail validationProblem(String detail, List<String> errors) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_ERROR, "Validation failed", detail);
        problem.setProperty("errors", errors);
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, URI type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        return problem;
    }
}
