package ge.tsu.redlink.roversim.rover;

import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.rover.RoverMode;
import ge.tsu.redlink.roversim.flight.RoverSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * The rover's true state. Changed only by the executor; optimistic locking guards against two
 * writers.
 */
@Entity
@Table(name = "rover_state")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA needs it; nobody else may create an empty one
public class RoverState {

    @Id
    @Column(name = "rover_id")
    private Long roverId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "site_id", nullable = false, length = 64)
    private String siteId;

    @Column(nullable = false)
    private int x;

    @Column(nullable = false)
    private int y;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Heading heading;

    @Column(name = "battery_percent", nullable = false)
    private double batteryPercent;

    @Column(name = "temperature_celsius", nullable = false)
    private double temperatureCelsius;

    @Column(name = "storage_used_percent", nullable = false)
    private int storageUsedPercent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RoverMode mode;

    @Column(name = "last_sequence_no", nullable = false)
    private long lastSequenceNo;

    /** A dust storm lasts until then; null when the sky is clear. */
    @Column(name = "dust_storm_until")
    private Instant dustStormUntil;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public RoverSnapshot snapshot() {
        return new RoverSnapshot(x, y, heading, batteryPercent, temperatureCelsius, storageUsedPercent, mode);
    }

    public void apply(RoverSnapshot snapshot, Instant now) {
        this.x = snapshot.x();
        this.y = snapshot.y();
        this.heading = snapshot.heading();
        this.batteryPercent = snapshot.batteryPercent();
        this.temperatureCelsius = snapshot.temperatureCelsius();
        this.storageUsedPercent = snapshot.storageUsedPercent();
        this.mode = snapshot.mode();
        this.updatedAt = now;
    }

    public boolean inDustStorm(Instant now) {
        return dustStormUntil != null && now.isBefore(dustStormUntil);
    }

    public void startDustStorm(Instant until, Instant now) {
        this.dustStormUntil = until;
        this.updatedAt = now;
    }

    public void endDustStorm(Instant now) {
        this.dustStormUntil = null;
        this.updatedAt = now;
    }

    /** The rover has taken (started, refused or skipped past) this sequence number. */
    public void advanceSequence(long sequenceNo, Instant now) {
        if (sequenceNo <= lastSequenceNo) {
            throw new IllegalStateException("Sequence " + sequenceNo + " is not after " + lastSequenceNo);
        }
        this.lastSequenceNo = sequenceNo;
        this.updatedAt = now;
    }

}
