package ge.tsu.redlink.missioncontrol.mission;

import ge.tsu.redlink.contract.command.CommandType;
import ge.tsu.redlink.missioncontrol.web.ApiExceptions.ConflictException;
import ge.tsu.redlink.missioncontrol.web.ApiExceptions.NotFoundException;
import ge.tsu.redlink.missioncontrol.web.ApiExceptions.UnprocessableException;
import ge.tsu.redlink.missioncontrol.web.ProblemTypes;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class MissionExceptions {

    static class MissionNotFoundException extends NotFoundException {

        MissionNotFoundException(long missionId) {
            super(ProblemTypes.MISSION_NOT_FOUND, "Mission " + missionId + " does not exist");
        }
    }

    /** 422: valid steps, but the rover could not carry out the plan; {@code step} is one-based. */
    static class PlanInfeasibleException extends UnprocessableException {

        private final int step;
        private final String error;

        PlanInfeasibleException(int step, CommandType type, String reason) {
            super(ProblemTypes.MISSION_PLAN_INFEASIBLE, "Mission plan infeasible",
                    "Step " + step + " (" + type + ") cannot be carried out by Earth's last knowledge of the rover");
            this.step = step;
            this.error = "steps[" + (step - 1) + "]: " + reason;
        }

        @Override
        public Map<String, Object> properties() {
            return Map.of("step", step, "errors", List.of(error));
        }
    }

    /** 409: the plan must start from a known state, so the rover's queue has to be empty. */
    static class RoverBusyException extends ConflictException {

        RoverBusyException(long roverId) {
            super(ProblemTypes.ROVER_BUSY, "Rover busy",
                    "Rover " + roverId + " still has unfinished commands; a mission is planned from a known state, "
                            + "so wait until they finish (or cancel them)");
        }
    }

    /** 409: every step has already left Earth or ended. */
    static class MissionNotCancellableException extends ConflictException {

        MissionNotCancellableException(long missionId, MissionStatus status) {
            super(ProblemTypes.MISSION_NOT_CANCELLABLE, "Mission not cancellable",
                    "Mission " + missionId + " is " + status + " and has no steps left on Earth");
        }
    }
}
