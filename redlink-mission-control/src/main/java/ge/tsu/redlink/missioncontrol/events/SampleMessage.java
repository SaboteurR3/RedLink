package ge.tsu.redlink.missioncontrol.events;

import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.telemetry.SampleMaterial;

import java.time.Instant;

/**
 * A drill sample the rover reported ({@code sample} event).
 *
 * @param x         where the rover stood; a rock sample comes from the next cell in {@code heading}
 * @param roverTime when it was taken on Mars
 */
public record SampleMessage(long commandId, SampleMaterial material, int x, int y, Heading heading, Instant roverTime) {
}
