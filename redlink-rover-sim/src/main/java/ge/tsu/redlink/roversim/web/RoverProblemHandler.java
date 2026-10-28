package ge.tsu.redlink.roversim.web;

import ge.tsu.redlink.roversim.uplink.UplinkExceptions.UnknownRoverException;
import ge.tsu.redlink.roversim.uplink.UplinkExceptions.UplinkConflictException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;

/**
 * Problem Details for everything rover-sim answers. Extends Spring's handler so standard MVC errors
 * (405, 415, ...) stay RFC 9457 too; replaces Boot's default one.
 */
@RestControllerAdvice
@Slf4j
public class RoverProblemHandler extends ResponseEntityExceptionHandler {

    /** A packet that is not a valid dictionary command (e.g. MOVE 0). */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_PACKET, "Invalid packet",
                "The packet violates the command dictionary");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /** Unparseable JSON or an unknown command type. The parser's message is not echoed back. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_PACKET, "Invalid packet",
                "The packet is not valid JSON or names a command that is not in the dictionary");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @ExceptionHandler(UnknownRoverException.class)
    ProblemDetail unknownRover(UnknownRoverException e) {
        return problem(HttpStatus.NOT_FOUND, ProblemTypes.UNKNOWN_ROVER, "Unknown rover", e.getMessage());
    }

    @ExceptionHandler(UplinkConflictException.class)
    ProblemDetail conflict(UplinkConflictException e) {
        URI type = switch (e.getKind()) {
            case PACKET_ID_REUSED -> ProblemTypes.PACKET_CONFLICT;
            case SEQUENCE_TAKEN -> ProblemTypes.SEQUENCE_CONFLICT;
        };
        return problem(HttpStatus.CONFLICT, type, "Packet conflict", e.getMessage());
    }

    /** Anything unexpected: logged in full, answered without internals. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL_ERROR, "Internal error",
                "The flight software could not process the request");
    }

    private static ProblemDetail problem(HttpStatus status, URI type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        return problem;
    }
}
