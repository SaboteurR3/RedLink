package ge.tsu.redlink.roversim.uplink;

import ge.tsu.redlink.contract.command.CommandType;
import ge.tsu.redlink.contract.telemetry.CommandExecutionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A command packet the rover has accepted. Inserted by {@link ReceivedCommandRepository#insertIfAbsent}
 * (race-free dedupe); then RECEIVED → EXECUTING → COMPLETED/FAILED, or RECEIVED → REFUSED. The
 * transition methods reject any other path.
 */
@Entity
@Table(name = "received_commands")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA needs it; nobody else may create an empty one
public class ReceivedCommand {

    @Id
    @Column(name = "packet_id")
    private UUID packetId;

    @Column(name = "rover_id", nullable = false)
    private long roverId;

    @Column(name = "command_id", nullable = false)
    private long commandId;

    @Column(name = "sequence_no", nullable = false)
    private long sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CommandType type;

    /** The {@link ge.tsu.redlink.contract.command.RoverCommand} as JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String command;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CommandExecutionStatus status;

    @Column(name = "progress_percent", nullable = false)
    private int progressPercent;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_detail", length = 500)
    private String failureDetail;

    @Column(name = "total_steps")
    private Integer totalSteps;

    @Column(name = "steps_done", nullable = false)
    private int stepsDone;

    @Column(name = "next_step_at")
    private Instant nextStepAt;

    /** Earth's trace context from the packet: executing and reporting continue that trace. */
    @Column(name = "trace_parent", updatable = false)
    private String traceParent;

    public void start(int totalSteps, Instant firstStepAt, Instant now) {
        requireStatus(CommandExecutionStatus.RECEIVED);
        this.status = CommandExecutionStatus.EXECUTING;
        this.totalSteps = totalSteps;
        this.startedAt = now;
        this.nextStepAt = firstStepAt;
    }

    public void stepDone(Instant nextStepAt) {
        requireStatus(CommandExecutionStatus.EXECUTING);
        this.stepsDone++;
        this.progressPercent = stepsDone * 100 / totalSteps;
        this.nextStepAt = nextStepAt;
    }

    public boolean allStepsDone() {
        return totalSteps != null && stepsDone >= totalSteps;
    }

    public void complete(Instant now) {
        requireStatus(CommandExecutionStatus.EXECUTING);
        finish(CommandExecutionStatus.COMPLETED, now);
        this.progressPercent = 100;
    }

    public void fail(String code, String detail, Instant now) {
        requireStatus(CommandExecutionStatus.EXECUTING);
        finish(CommandExecutionStatus.FAILED, now);
        this.failureCode = code;
        this.failureDetail = detail;
    }

    /** Never started: safe mode, or its sequence number was already skipped. */
    public void refuse(String code, String detail, Instant now) {
        requireStatus(CommandExecutionStatus.RECEIVED);
        finish(CommandExecutionStatus.REFUSED, now);
        this.failureCode = code;
        this.failureDetail = detail;
    }

    private void finish(CommandExecutionStatus finalStatus, Instant now) {
        this.status = finalStatus;
        this.finishedAt = now;
        this.nextStepAt = null;
    }

    private void requireStatus(CommandExecutionStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Command " + commandId + " is " + status + ", expected " + expected);
        }
    }

}
