package ge.tsu.redlink.missioncontrol.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import ge.tsu.redlink.contract.command.CommandType;
import ge.tsu.redlink.contract.command.RoverCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A command as the API shows it, with hypermedia links. {@code _links} is the set of things the
 * client may do next: {@code cancel} appears only while the command can still be cancelled, so a
 * client never has to re-implement the lifecycle rules to decide which buttons to show.
 *
 * @param sequenceNo position in the rover's uplink order, assigned when transmitted
 */
public record CommandResponse(
        long id,
        long roverId,
        CommandType type,
        RoverCommand command,
        CommandStatus status,
        int progressPercent,
        Long sequenceNo,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant transmittedAt,
        Instant finishedAt,
        Failure failure,
        Approval approval,
        @Schema(description = "Present when the command is a step of a mission")
        MissionStep mission,
        @JsonProperty("_links")
        @Schema(description = "Actions the command's state allows now: self, rover; cancel while it has not left Earth; "
                + "approve and reject while it waits for approval. Role and four-eyes rule are checked on every call.")
        Map<String, Link> links) {

    static String selfPath(long commandId) {
        return "/api/v1/commands/" + commandId;
    }

    /** For callers holding only the entity: parses its stored command JSON. */
    public static CommandResponse of(Command command, JsonMapper jsonMapper) {
        return of(command, jsonMapper.readValue(command.getCommand(), RoverCommand.class));
    }

    public static CommandResponse of(Command command, RoverCommand parsed) {
        Map<String, Link> links = new LinkedHashMap<>();
        links.put("self", Link.get(selfPath(command.getId())));
        links.put("rover", Link.get("/api/v1/rovers/" + command.getRoverId()));
        if (command.getMissionId() != null) {
            links.put("mission", Link.get("/api/v1/missions/" + command.getMissionId()));
        }
        if (command.getStatus().isCancellable()) {
            links.put("cancel", new Link(selfPath(command.getId()) + "/cancel", "POST"));
        }
        if (command.getStatus() == CommandStatus.PENDING_APPROVAL) {
            links.put("approve", new Link(selfPath(command.getId()) + "/approve", "POST"));
            links.put("reject", new Link(selfPath(command.getId()) + "/reject", "POST"));
        }
        Approval approval = command.approvalState() == null ? null : new Approval(command.approvalState(),
                command.getDecidedBy(), command.getDecidedAt(), command.getDecisionReason());
        Failure failure = command.getFailureCode() == null ? null
                : new Failure(command.getFailureCode(), command.getFailureDetail());
        MissionStep mission = command.getMissionId() == null ? null
                : new MissionStep(command.getMissionId(), command.getMissionStep());
        return new CommandResponse(command.getId(), command.getRoverId(), command.getType(), parsed,
                command.getStatus(), command.getProgressPercent(), command.getSequenceNo(), command.getCreatedBy(),
                command.getCreatedAt(), command.getUpdatedAt(), command.getTransmittedAt(), command.getFinishedAt(),
                failure, approval, mission, links);
    }

    /**
     * Present only for commands that need approval.
     *
     * @param reason why it was rejected, if given
     */
    public record Approval(ApprovalState state, String decidedBy, Instant decidedAt, String reason) {
    }

    /** @param step one-based position in the mission */
    public record MissionStep(long id, int step) {
    }

    /** Why the command failed or was refused, as reported by the rover. */
    public record Failure(String code, String detail) {
    }

    /**
     * A hypermedia link. {@code method} is given for anything but GET.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Link(String href, String method) {

        static Link get(String href) {
            return new Link(href, null);
        }
    }
}
