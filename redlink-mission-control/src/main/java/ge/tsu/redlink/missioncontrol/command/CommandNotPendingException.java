package ge.tsu.redlink.missioncontrol.command;

import ge.tsu.redlink.missioncontrol.web.ApiException;
import ge.tsu.redlink.missioncontrol.web.ProblemTypes;
import org.springframework.http.HttpStatus;

/**
 * 409: the decision contradicts the command's state (already decided otherwise, cancelled, or the
 * command never needed approval).
 */
public class CommandNotPendingException extends ApiException {

    public CommandNotPendingException(Long commandId, CommandStatus status) {
        super(HttpStatus.CONFLICT, ProblemTypes.COMMAND_NOT_PENDING_APPROVAL, "Command not pending approval",
                "Command " + commandId + " is " + status + "; only a command waiting for approval can be approved or rejected");
    }
}
