package ge.tsu.redlink.missioncontrol.telemetry;

import ge.tsu.redlink.contract.telemetry.TelemetryType;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One received telemetry packet. Written once by {@link TelemetryService} (race-free insert), then
 * only read: history never changes.
 */
@Entity
@Immutable
@Table(name = "telemetry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA needs it; nobody else may create an empty one
public class TelemetryRecord {

    @Id
    private Long id;

    @Column(name = "packet_id", nullable = false)
    private UUID packetId;

    @Column(name = "rover_id", nullable = false)
    private long roverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TelemetryType type;

    @Column(name = "command_id")
    private Long commandId;

    /** The {@link ge.tsu.redlink.contract.telemetry.TelemetryPayload} as JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(name = "rover_time", nullable = false)
    private Instant roverTime;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

}
