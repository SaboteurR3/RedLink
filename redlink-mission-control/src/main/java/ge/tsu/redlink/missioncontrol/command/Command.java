package ge.tsu.redlink.missioncontrol.command;

import ge.tsu.redlink.contract.command.CommandType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A command an operator sent to a rover. State changes go through methods that enforce the
 * lifecycle in {@link CommandStatus}; {@code @Version} serialises concurrent changes (an operator
 * cancelling while the uplink dispatcher transmits).
 */
@Entity
@Table(name = "commands")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA needs it; nobody else may create an empty one
public class Command {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rover_id", nullable = false)
    private long roverId;

    @Column(name = "sequence_no")
    private Long sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CommandType type;

    /** The {@link ge.tsu.redlink.contract.command.RoverCommand} as JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String command;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private CommandStatus status;

    @Column(name = "progress_percent", nullable = false)
    private int progressPercent;

    @Column(name = "created_by", nullable = false, length = 64, updatable = false)
    private String createdBy;

    @Column(name = "cancelled_by", length = 64)
    private String cancelledBy;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_detail", length = 500)
    private String failureDetail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "transmitted_at")
    private Instant transmittedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** When it became QUEUED (creation or approval); the uplink hold counts from here. */
    @Column(name = "queued_at")
    private Instant queuedAt;

    @Column(name = "decided_by", length = 64)
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    /** The mission this command is a step of, if any. */
    @Column(name = "mission_id", updatable = false)
    private Long missionId;

    /** One-based position in its mission. */
    @Column(name = "mission_step", updatable = false)
    private Integer missionStep;

    /** W3C traceparent of the request that created it; the uplink continues this trace. */
    @Column(name = "trace_parent", length = 55, updatable = false)
    private String traceParent;

    @Version
    @Column(nullable = false)
    private long version;

    Command(long roverId, CommandType type, String commandJson, String createdBy, Instant now) {
        this.roverId = roverId;
        this.type = type;
        this.command = commandJson;
        this.status = type.requiresApproval() ? CommandStatus.PENDING_APPROVAL : CommandStatus.QUEUED;
        this.queuedAt = type.requiresApproval() ? null : now;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Remembers the trace this command was created in (before it is first saved). */
    public Command tracedAs(String traceparent) {
        this.traceParent = traceparent;
        return this;
    }

    /** Step {@code step} (one-based) of mission {@code missionId}. */
    public Command(long roverId, CommandType type, String commandJson, String createdBy, Instant now,
                   long missionId, int step) {
        this(roverId, type, commandJson, createdBy, now);
        this.missionId = missionId;
        this.missionStep = step;
    }

    /**
     * An earlier step of its mission ended badly, so this one, still on Earth, will not go.
     *
     * @return false if it has already left Earth or finished (nothing to withdraw)
     */
    public boolean abortWithMission(int failedStep, Instant now) {
        if (!status.isCancellable()) {
            return false;
        }
        this.status = CommandStatus.CANCELLED;
        this.failureCode = "MISSION_ABORTED";
        this.failureDetail = "Step " + failedStep + " of mission " + missionId + " did not complete";
        this.finishedAt = now;
        this.updatedAt = now;
        return true;
    }

    /** Final, and not because it succeeded. */
    public boolean endedUnsuccessfully() {
        return status.isFinal() && status != CommandStatus.COMPLETED;
    }

    /**
     * A flight director lets the command go. Retrying an approval that already happened succeeds
     * again without change.
     *
     * @return false if it had already been approved
     * @throws FourEyesViolationException  if the director created the command
     * @throws CommandNotPendingException  if it was rejected, cancelled or never needed approval
     */
    boolean approve(String director, Instant now) {
        if (status == CommandStatus.PENDING_APPROVAL) {
            if (director.equals(createdBy)) {
                throw new FourEyesViolationException(id);
            }
            this.status = CommandStatus.QUEUED;
            this.queuedAt = now;
            decide(director, null, now);
            return true;
        }
        if (approvalState() == ApprovalState.APPROVED) {
            return false;
        }
        throw new CommandNotPendingException(id, status);
    }

    /**
     * A flight director stops the command for good. Retrying a rejection succeeds again without change.
     *
     * @return false if it had already been rejected
     * @throws CommandNotPendingException if it was approved, cancelled or never needed approval
     */
    boolean reject(String director, String reason, Instant now) {
        if (status == CommandStatus.PENDING_APPROVAL) {
            this.status = CommandStatus.REJECTED;
            this.finishedAt = now;
            decide(director, reason, now);
            return true;
        }
        if (status == CommandStatus.REJECTED) {
            return false;
        }
        throw new CommandNotPendingException(id, status);
    }

    private void decide(String director, String reason, Instant now) {
        this.decidedBy = director;
        this.decidedAt = now;
        this.decisionReason = reason;
        this.updatedAt = now;
    }

    /** {@code null} for commands that need no approval. */
    public ApprovalState approvalState() {
        if (!type.requiresApproval()) {
            return null;
        }
        if (decidedBy == null) {
            return status == CommandStatus.CANCELLED ? ApprovalState.WITHDRAWN : ApprovalState.PENDING;
        }
        return status == CommandStatus.REJECTED ? ApprovalState.REJECTED : ApprovalState.APPROVED;
    }

    /**
     * @return false if it was already cancelled (cancelling twice is not an error)
     * @throws CommandNotCancellableException once the command has left Earth or is finished
     */
    public boolean cancel(String user, Instant now) {
        if (status == CommandStatus.CANCELLED) {
            return false;
        }
        if (!status.isCancellable()) {
            throw new CommandNotCancellableException(id, status);
        }
        this.status = CommandStatus.CANCELLED;
        this.cancelledBy = user;
        this.finishedAt = now;
        this.updatedAt = now;
        return true;
    }

    /** Leaves Earth: from now on it cannot be called back. */
    public void transmit(long sequenceNo, Instant now) {
        requireStatus(CommandStatus.QUEUED);
        this.status = CommandStatus.TRANSMITTED;
        this.sequenceNo = sequenceNo;
        this.transmittedAt = now;
        this.updatedAt = now;
    }

    /** The rover permanently refused the uplink packet itself (not the command's execution). */
    public void uplinkRejected(String detail, Instant now) {
        requireStatus(CommandStatus.TRANSMITTED);
        this.status = CommandStatus.FAILED;
        this.failureCode = "UPLINK_REJECTED";
        this.failureDetail = detail;
        this.finishedAt = now;
        this.updatedAt = now;
    }

    /**
     * Applies a status report from the rover. Telemetry can arrive repeated or out of order, so the
     * status only moves forward ({@link CommandStatus#lifecycleRank()}); within EXECUTING only higher
     * progress counts, and final states never change.
     *
     * @param roverTime when it happened on Mars (becomes {@code finishedAt} for final states)
     * @return true if the report changed the command
     */
    public boolean applyRoverReport(CommandStatus reported, int progress, String code, String detail,
                                    Instant roverTime, Instant now) {
        if (status.isFinal() || status.lifecycleRank() == 0) {
            return false; // finished, or never left Earth (a report about it cannot be genuine)
        }
        int current = status.lifecycleRank();
        int next = reported.lifecycleRank();
        boolean moreProgress = reported == CommandStatus.EXECUTING && status == CommandStatus.EXECUTING
                && progress > progressPercent;
        if (next < current || next == current && !moreProgress) {
            return false;
        }
        this.status = reported;
        this.progressPercent = reported == CommandStatus.COMPLETED ? 100 : Math.max(progressPercent, progress);
        if (reported.isFinal()) {
            this.finishedAt = roverTime;
            this.failureCode = code;
            this.failureDetail = detail;
        }
        this.updatedAt = now;
        return true;
    }

    private void requireStatus(CommandStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Command " + id + " is " + status + ", expected " + expected);
        }
    }

}
