package ge.tsu.redlink.missioncontrol.command;

import ge.tsu.redlink.missioncontrol.web.ApiException;
import ge.tsu.redlink.missioncontrol.web.ProblemTypes;
import org.springframework.http.HttpStatus;

/**
 * 409: the command has already been transmitted (or finished); nothing can call it back.
 */
public class CommandNotCancellableException extends ApiException {

    public CommandNotCancellableException(Long commandId, CommandStatus status) {
        super(HttpStatus.CONFLICT, ProblemTypes.COMMAND_NOT_CANCELLABLE, "Command not cancellable",
                "Command " + commandId + " is " + status + (status.isFinal()
                        ? ": it is finished and can no longer be cancelled"
                        : ": it is already on its way to Mars and can no longer be cancelled"));
    }
}
