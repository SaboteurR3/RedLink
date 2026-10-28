package ge.tsu.redlink.missioncontrol.photo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import ge.tsu.redlink.contract.rover.Camera;
import ge.tsu.redlink.contract.rover.Heading;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A photo's metadata; the image itself is the same resource requested with {@code Accept: image/png}.
 *
 * @param x        grid column where the rover stood
 * @param y        grid row where the rover stood
 * @param heading  the way the camera looked
 * @param takenAt  Mars time
 */
public record PhotoResponse(
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
        Instant takenAt,
        Instant receivedAt,
        @JsonProperty("_links")
        @Schema(description = "self, rover, the command that took it, and image: the same URL as self, "
                + "requested with Accept: image/png")
        Map<String, PhotoLink> links) {

    static String selfPath(UUID id) {
        return "/api/v1/photos/" + id;
    }

    static PhotoResponse of(PhotoMetadata photo) {
        Map<String, PhotoLink> links = new LinkedHashMap<>();
        links.put("self", new PhotoLink(selfPath(photo.id()), MediaType.APPLICATION_JSON_VALUE));
        links.put("image", new PhotoLink(selfPath(photo.id()), MediaType.IMAGE_PNG_VALUE));
        links.put("rover", new PhotoLink("/api/v1/rovers/" + photo.roverId(), null));
        links.put("command", new PhotoLink("/api/v1/commands/" + photo.commandId(), null));
        return new PhotoResponse(photo.id(), photo.roverId(), photo.commandId(), photo.camera(), photo.x(), photo.y(),
                photo.heading(), photo.width(), photo.height(), photo.sizeBytes(), photo.takenAt(), photo.receivedAt(),
                links);
    }

    /**
     * @param type the media type to ask for in {@code Accept}, where the URL has several
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PhotoLink(String href, String type) {
    }
}
