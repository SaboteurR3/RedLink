package ge.tsu.redlink.missioncontrol.photo;

import ge.tsu.redlink.contract.telemetry.TelemetryPayload;
import ge.tsu.redlink.missioncontrol.rover.RoverRepository;
import ge.tsu.redlink.missioncontrol.web.ApiExceptions.InvalidRequestException;
import ge.tsu.redlink.missioncontrol.web.ApiExceptions.NotFoundException;
import ge.tsu.redlink.missioncontrol.web.ProblemTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PhotoService {

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final int MAX_SIDE = 4096;

    private final PhotoRepository photos;
    private final RoverRepository rovers;
    private final JdbcClient jdbc;

    /**
     * Stores a downlinked photo in the caller's transaction (the one that stores its telemetry, so
     * a repeated packet never gets here). The photo's id is the packet's id.
     *
     * @throws InvalidRequestException if the image is not a PNG: the whole packet is refused
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PhotoResponse store(long roverId, UUID packetId, TelemetryPayload.Photo photo, Instant takenAt, Instant now) {
        byte[] png = decode(photo.imageBase64());
        // IHDR is always the first chunk: width and height are at fixed offsets.
        ByteBuffer header = ByteBuffer.wrap(png);
        int width = header.getInt(16);
        int height = header.getInt(20);
        if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE) {
            throw invalid("PNG size " + width + "x" + height + " is outside 1–" + MAX_SIDE);
        }
        String sha256 = sha256(png);
        jdbc.sql("""
                        INSERT INTO photos (id, rover_id, command_id, camera, x, y, heading, width, height, size_bytes,
                                            sha256, image, taken_at, received_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(packetId, roverId, photo.commandId(), photo.camera().name(), photo.x(), photo.y(),
                        photo.heading().name(), width, height, png.length, sha256, png,
                        Timestamp.from(takenAt), Timestamp.from(now))
                .update();
        return PhotoResponse.of(new PhotoMetadata(packetId, roverId, photo.commandId(), photo.camera(), photo.x(),
                photo.y(), photo.heading(), width, height, png.length, sha256, takenAt, now));
    }

    @Transactional(readOnly = true)
    public Page<PhotoResponse> list(long roverId, Pageable pageable) {
        if (!rovers.existsById(roverId)) {
            throw new NotFoundException(ProblemTypes.ROVER_NOT_FOUND, "Rover " + roverId + " does not exist");
        }
        return photos.findByRoverId(roverId, pageable).map(PhotoResponse::of);
    }

    /**
     * @return the metadata with the hash of the image, from which the JSON's entity tag is made
     */
    @Transactional(readOnly = true)
    public PhotoMetadata metadata(UUID id) {
        return photos.findMetadataById(id).orElseThrow(() -> new PhotoNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public PhotoImage image(UUID id) {
        return photos.findImageById(id).orElseThrow(() -> new PhotoNotFoundException(id));
    }

    private static byte[] decode(String base64) {
        byte[] png;
        try {
            png = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw invalid("imageBase64 is not valid base64");
        }
        if (png.length < 24 || !Arrays.equals(png, 0, 8, PNG_SIGNATURE, 0, 8)
                || !"IHDR".equals(new String(png, 12, 4, StandardCharsets.US_ASCII))) {
            throw invalid("imageBase64 is not a PNG");
        }
        return png;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM has SHA-256", e);
        }
    }

    private static InvalidRequestException invalid(String error) {
        return new InvalidRequestException("The photo cannot be stored", List.of("payload." + error));
    }
}
