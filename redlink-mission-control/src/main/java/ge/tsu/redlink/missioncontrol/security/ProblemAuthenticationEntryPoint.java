package ge.tsu.redlink.missioncontrol.security;

import ge.tsu.redlink.missioncontrol.web.ProblemResponses;
import ge.tsu.redlink.missioncontrol.web.ProblemTypes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * 401 for missing or invalid tokens: the standard {@code WWW-Authenticate: Bearer ...} header
 * (RFC 6750 §3) plus a Problem Details body.
 */
@RequiredArgsConstructor
class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
    private final ProblemResponses problems;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        bearer.commence(request, response, exception);
        String detail = exception instanceof InvalidBearerTokenException
                ? "The access token is invalid or has expired"
                : "Authentication is required";
        problems.write(request, response, HttpStatus.UNAUTHORIZED, ProblemTypes.UNAUTHENTICATED, "Unauthenticated", detail);
    }
}
