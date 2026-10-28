package ge.tsu.redlink.roversim.flight;

/**
 * Result of one execution step.
 */
public sealed interface StepOutcome {

    RoverSnapshot rover();

    record Advanced(RoverSnapshot rover) implements StepOutcome {
    }

    /** {@code rover} is the state at the moment of failure (nothing of the failed step applied). */
    record Failed(FailureCode code, String detail, RoverSnapshot rover) implements StepOutcome {
    }
}
