package ge.tsu.redlink.missioncontrol.photo;

import ge.tsu.redlink.contract.rover.Camera;
import ge.tsu.redlink.contract.rover.Heading;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * A photo as received from the rover. Written once by {@link PhotoService#store} and never
 * changed, so Hibernate treats it as read-only.
 */
@Entity
@Table(name = "photos")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA needs it; nobody else may create an empty one
public class Photo {

    @Id
    private UUID id;

    @Column(name = "rover_id")
    private long roverId;

    @Column(name = "command_id")
    private long commandId;

    @Enumerated(EnumType.STRING)
    private Camera camera;

    private int x;

    private int y;

    @Enumerated(EnumType.STRING)
    private Heading heading;

    private int width;

    private int height;

    @Column(name = "size_bytes")
    private int sizeBytes;

    private String sha256;

    /** Lists and JSON never load it: they read {@link PhotoMetadata} instead. */
    private byte[] image;

    @Column(name = "taken_at")
    private Instant takenAt;

    @Column(name = "received_at")
    private Instant receivedAt;
}
