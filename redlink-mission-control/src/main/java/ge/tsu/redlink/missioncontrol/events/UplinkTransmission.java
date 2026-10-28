package ge.tsu.redlink.missioncontrol.events;

import java.time.Instant;

/**
 * A command left Earth ({@code uplink} event). {@code arrivesAt} lets the frontend animate the
 * signal on its way to Mars.
 */
public record UplinkTransmission(long commandId, long sequenceNo, Instant transmittedAt, Instant arrivesAt) {
}
