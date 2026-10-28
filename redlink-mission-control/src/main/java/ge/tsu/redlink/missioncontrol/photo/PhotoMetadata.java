package ge.tsu.redlink.missioncontrol.photo;

import ge.tsu.redlink.contract.rover.Camera;
import ge.tsu.redlink.contract.rover.Heading;

import java.time.Instant;
import java.util.UUID;

/**
 * Everything about a {@link Photo} except the image: a Spring Data projection, so lists and JSON
 * responses never load the bytes.
 */
public record PhotoMetadata(
        UUID id,
        long roverId,
        long commandId,
        Camera camera,
        int x,
        int y,
        Heading heading,
        int width,
        int height,
        int sizeBytes,
        String sha256,
        Instant takenAt,
        Instant receivedAt) {
}
