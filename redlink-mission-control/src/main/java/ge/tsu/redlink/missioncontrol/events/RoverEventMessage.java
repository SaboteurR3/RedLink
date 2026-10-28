package ge.tsu.redlink.missioncontrol.events;

import ge.tsu.redlink.contract.telemetry.EventSeverity;

import java.time.Instant;

/**
 * Something the rover reported that is not tied to a command ({@code rover-event} event).
 *
 * @param roverTime when it happened on Mars
 */
public record RoverEventMessage(String code, EventSeverity severity, String message, Instant roverTime) {
}
