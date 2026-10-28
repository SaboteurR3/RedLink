package ge.tsu.redlink.missioncontrol.command;

import ge.tsu.redlink.missioncontrol.web.ApiException;
import ge.tsu.redlink.missioncontrol.web.ProblemTypes;
import org.springframework.http.HttpStatus;

/**
 * 403: a risky command needs two different people — one to ask, another to approve. The role
 * allows approving; approving one's own command is what is forbidden.
 */
public class FourEyesViolationException extends ApiException {

    public FourEyesViolationException(Long commandId) {
        super(HttpStatus.FORBIDDEN, ProblemTypes.FOUR_EYES_VIOLATION, "Four-eyes rule",
                "Command " + commandId + " was created by you; another flight director must approve it");
    }
}
