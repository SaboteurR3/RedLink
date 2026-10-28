package ge.tsu.redlink.missioncontrol.auth;

import ge.tsu.redlink.missioncontrol.web.ProblemTypes;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Login failures as Problem Details. Limited to {@link AuthController} so it never intercepts
 * security exceptions from other endpoints, which the security filter chain handles.
 *
 * <p>Highest precedence: Spring asks advices in order and takes the first that can handle the
 * exception, and the global {@code ApiProblemHandler} would otherwise answer an
 * {@code AuthenticationException} with its catch-all 500.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AuthController.class)
class AuthExceptionHandler {

    /**
     * Same answer for unknown user, wrong password and disabled account: the response must not
     * reveal which usernames exist.
     */
    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail invalidCredentials() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        problem.setType(ProblemTypes.INVALID_CREDENTIALS);
        problem.setTitle("Invalid credentials");
        return problem;
    }

    @ExceptionHandler(LoginThrottledException.class)
    ResponseEntity<ProblemDetail> throttled(LoginThrottledException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Too many failed login attempts. Try again in " + e.retryAfterSeconds() + " seconds.");
        problem.setType(ProblemTypes.TOO_MANY_LOGIN_ATTEMPTS);
        problem.setTitle("Too many login attempts");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .body(problem);
    }
}
