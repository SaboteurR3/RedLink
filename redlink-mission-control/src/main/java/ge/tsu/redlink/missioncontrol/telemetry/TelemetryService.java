package ge.tsu.redlink.missioncontrol.telemetry;

import ge.tsu.redlink.contract.rover.RoverMode;
import ge.tsu.redlink.contract.telemetry.TelemetryPacket;
import ge.tsu.redlink.contract.telemetry.TelemetryPayload;
import ge.tsu.redlink.contract.telemetry.TelemetryType;
import ge.tsu.redlink.missioncontrol.command.CommandEnded;
import ge.tsu.redlink.missioncontrol.command.CommandRepository;
import ge.tsu.redlink.missioncontrol.command.CommandResponse;
import ge.tsu.redlink.missioncontrol.command.CommandStatus;
import ge.tsu.redlink.missioncontrol.events.EventType;
import ge.tsu.redlink.missioncontrol.events.RoverEventMessage;
import ge.tsu.redlink.missioncontrol.events.RoverEventPublisher;
import ge.tsu.redlink.missioncontrol.events.SampleMessage;
import ge.tsu.redlink.missioncontrol.photo.PhotoResponse;
import ge.tsu.redlink.missioncontrol.photo.PhotoService;
import ge.tsu.redlink.missioncontrol.rover.Rover;
import ge.tsu.redlink.missioncontrol.rover.RoverRepository;
import ge.tsu.redlink.missioncontrol.rover.RoverResponse;
import ge.tsu.redlink.missioncontrol.telemetry.TelemetryRecordRepository.Filters;
import ge.tsu.redlink.missioncontrol.web.ApiExceptions.InvalidRequestException;
import ge.tsu.redlink.missioncontrol.web.ApiExceptions.NotFoundException;
import ge.tsu.redlink.missioncontrol.web.ApiExceptions.UnprocessableException;
import ge.tsu.redlink.missioncontrol.web.ProblemTypes;
import ge.tsu.redlink.missioncontrol.webhook.WebhookEvent;
import ge.tsu.redlink.missioncontrol.webhook.WebhookNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * What Earth does with the rover's reports. Every packet is applied exactly once (dedupe on its
 * packet id) and in a forward-only way, because the downlink may repeat and reorder packets.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TelemetryService {

    private final JdbcClient jdbc;
    private final RoverRepository rovers;
    private final CommandRepository commands;
    private final TelemetryRecordRepository records;
    private final JsonMapper jsonMapper;
    private final RoverEventPublisher events;
    private final WebhookNotifier webhooks;
    private final PhotoService photos;
    private final ApplicationEventPublisher ended;
    private final Clock clock;

    /**
     * Stores the packet and applies it, in one transaction.
     *
     * @return whether it was a repeated delivery (then nothing changed)
     * @throws UnprocessableException for an unknown rover (permanent: the sender should not retry)
     */
    @Transactional
    public DownlinkReceipt receive(TelemetryPacket packet) {
        Rover rover = rovers.findById(packet.roverId()).orElseThrow(() -> new UnprocessableException(
                ProblemTypes.UNKNOWN_ROVER, "Unknown rover", "Mission control has no rover " + packet.roverId()));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        TelemetryPayload payload = packet.payload();
        Long commandId = payload instanceof TelemetryPayload.CommandStatus status ? Long.valueOf(status.commandId())
                : payload instanceof TelemetryPayload.Photo photo ? Long.valueOf(photo.commandId())
                : payload instanceof TelemetryPayload.Sample sample ? Long.valueOf(sample.commandId()) : null;
        // The image goes to the photos table; telemetry keeps only what the photo was of.
        TelemetryPayload stored = payload instanceof TelemetryPayload.Photo photo ? photo.withoutImage() : payload;

        int inserted = jdbc.sql("""
                        INSERT INTO telemetry (packet_id, rover_id, type, command_id, payload, rover_time, received_at)
                        VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                        ON CONFLICT (packet_id) DO NOTHING""")
                .params(packet.packetId(), packet.roverId(), payload.type().name(), commandId,
                        jsonMapper.writeValueAsString(stored), Timestamp.from(packet.roverTime()), Timestamp.from(now))
                .update();
        if (inserted == 0) {
            log.debug("Duplicate telemetry packet {} ignored", packet.packetId());
            return new DownlinkReceipt(packet.packetId(), true);
        }

        switch (payload.type()) {
            case COMMAND_STATUS -> applyCommandStatus(rover, (TelemetryPayload.CommandStatus) payload, packet.roverTime(), now);
            case ROVER_STATE -> {
                RoverMode modeBefore = rover.getLastMode();
                double batteryBefore = rover.getLastBatteryPercent();
                if (rover.applyTelemetry((TelemetryPayload.RoverState) payload, packet.roverTime(), now)) {
                    RoverResponse view = RoverResponse.of(rover);
                    events.publish(rover.getId(), EventType.ROVER, view);
                    // Notify on the transition only, not on every report while it lasts.
                    if (modeBefore != RoverMode.SAFE_MODE && rover.getLastMode() == RoverMode.SAFE_MODE) {
                        webhooks.notify(WebhookEvent.ROVER_SAFE_MODE, view);
                    }
                    if (batteryBefore >= WebhookEvent.LOW_BATTERY_PERCENT
                            && rover.getLastBatteryPercent() < WebhookEvent.LOW_BATTERY_PERCENT) {
                        webhooks.notify(WebhookEvent.ROVER_BATTERY_LOW, view);
                    }
                } else {
                    log.debug("Older ROVER_STATE for rover {} ignored", rover.getId());
                }
            }
            case EVENT -> {
                TelemetryPayload.Event event = (TelemetryPayload.Event) payload;
                log.info("Rover {} event {} ({}): {}", rover.getId(), event.code(), event.severity(), event.message());
                events.publish(rover.getId(), EventType.ROVER_EVENT,
                        new RoverEventMessage(event.code(), event.severity(), event.message(), packet.roverTime()));
            }
            case PHOTO -> {
                PhotoResponse photo = photos.store(rover.getId(), packet.packetId(), (TelemetryPayload.Photo) payload,
                        packet.roverTime(), now);
                log.info("Rover {} sent photo {} ({} bytes)", rover.getId(), photo.id(), photo.sizeBytes());
                events.publish(rover.getId(), EventType.PHOTO, photo);
            }
            case SAMPLE -> {
                TelemetryPayload.Sample sample = (TelemetryPayload.Sample) payload;
                log.info("Rover {} took a {} sample at ({},{}) facing {}", rover.getId(), sample.material(), sample.x(),
                        sample.y(), sample.heading());
                events.publish(rover.getId(), EventType.SAMPLE, new SampleMessage(sample.commandId(), sample.material(),
                        sample.x(), sample.y(), sample.heading(), packet.roverTime()));
            }
        }
        return new DownlinkReceipt(packet.packetId(), false);
    }

    private void applyCommandStatus(Rover rover, TelemetryPayload.CommandStatus report, Instant roverTime, Instant now) {
        commands.findById(report.commandId())
                .filter(command -> command.getRoverId() == rover.getId())
                .ifPresentOrElse(command -> {
                    if (command.applyRoverReport(CommandStatus.fromRover(report.status()), report.progressPercent(),
                            report.failureCode(), report.failureDetail(), roverTime, now)) {
                        log.info("Command {} is now {} ({}%)", command.getId(), command.getStatus(), command.getProgressPercent());
                        CommandResponse view = CommandResponse.of(command, jsonMapper);
                        events.publish(rover.getId(), EventType.COMMAND, view);
                        switch (command.getStatus()) {
                            case COMPLETED -> webhooks.notify(WebhookEvent.COMMAND_COMPLETED, view);
                            case FAILED, REFUSED_BY_ROVER -> {
                                webhooks.notify(WebhookEvent.COMMAND_FAILED, view);
                                ended.publishEvent(new CommandEnded(command));
                            }
                            default -> {
                                // not a webhook event
                            }
                        }
                    }
                }, () -> log.warn("Rover {} reported on command {}, which Earth does not know for this rover",
                        rover.getId(), report.commandId()));
    }

    /**
     * @param from inclusive lower bound on Mars time, or null
     * @param to   exclusive upper bound on Mars time, or null
     */
    @Transactional(readOnly = true)
    public Page<TelemetryResponse> history(long roverId, TelemetryType type, Instant from, Instant to, Pageable pageable) {
        if (!rovers.existsById(roverId)) {
            throw new NotFoundException(ProblemTypes.ROVER_NOT_FOUND, "Rover " + roverId + " does not exist");
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new InvalidRequestException("The time range is empty", List.of("from: must be before to"));
        }
        Specification<TelemetryRecord> filter = Filters.ofRover(roverId);
        if (type != null) {
            filter = filter.and(Filters.ofType(type));
        }
        if (from != null) {
            filter = filter.and(Filters.from(from));
        }
        if (to != null) {
            filter = filter.and(Filters.until(to));
        }
        return records.findAll(filter, pageable).map(record -> new TelemetryResponse(record.getPacketId(),
                record.getType(), record.getCommandId(), record.getRoverTime(), record.getReceivedAt(),
                jsonMapper.readValue(record.getPayload(), TelemetryPayload.class)));
    }

    /**
     * @param duplicate true if this packet had been received before (nothing was changed)
     */
    public record DownlinkReceipt(UUID packetId, boolean duplicate) {
    }
}
