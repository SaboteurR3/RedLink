package ge.tsu.redlink.missioncontrol.rover;

import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.rover.RoverMode;
import ge.tsu.redlink.contract.telemetry.TelemetryPayload;
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
 * Earth's record of a rover: its name and the last state telemetry reported. Every change bumps
 * {@link #version}, which is also the HTTP entity tag.
 */
@Entity
@Table(name = "rovers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA needs it; nobody else may create an empty one
public class Rover {

    @Id
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "site_id", nullable = false, length = 64)
    private String siteId;

    @Column(name = "last_x", nullable = false)
    private int lastX;

    @Column(name = "last_y", nullable = false)
    private int lastY;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_heading", nullable = false, length = 8)
    private Heading lastHeading;

    @Column(name = "last_battery_percent", nullable = false)
    private double lastBatteryPercent;

    @Column(name = "last_temperature_celsius", nullable = false)
    private double lastTemperatureCelsius;

    @Column(name = "last_storage_used_percent", nullable = false)
    private int lastStorageUsedPercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_mode", nullable = false, length = 16)
    private RoverMode lastMode;

    @Column(name = "last_telemetry_at")
    private Instant lastTelemetryAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void rename(String newName, Instant now) {
        this.name = newName;
        this.updatedAt = now;
    }

    /**
     * Replaces the last known state with a rover report, unless Earth already knows a newer one
     * (telemetry may arrive out of order).
     *
     * @param roverTime when the state was recorded on Mars
     * @return true if applied
     */
    public boolean applyTelemetry(TelemetryPayload.RoverState state, Instant roverTime, Instant now) {
        if (lastTelemetryAt != null && roverTime.isBefore(lastTelemetryAt)) {
            return false;
        }
        this.lastX = state.x();
        this.lastY = state.y();
        this.lastHeading = state.heading();
        this.lastBatteryPercent = state.batteryPercent();
        this.lastTemperatureCelsius = state.temperatureCelsius();
        this.lastStorageUsedPercent = state.storageUsedPercent();
        this.lastMode = state.mode();
        this.lastTelemetryAt = roverTime;
        this.updatedAt = now;
        return true;
    }

}
