package ge.tsu.redlink.missioncontrol.security;

import ge.tsu.redlink.missioncontrol.web.ProblemResponses;
import ge.tsu.redlink.missioncontrol.web.ProblemTypes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * 403 for authenticated users whose role is not allowed to do this.
 */
@RequiredArgsConstructor
class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemResponses problems;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        problems.write(request, response, HttpStatus.FORBIDDEN, ProblemTypes.FORBIDDEN, "Forbidden",
                "Your role does not allow this action");
    }
}
