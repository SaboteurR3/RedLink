package ge.tsu.redlink.missioncontrol.mission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A named plan for one rover. Its steps are {@code Command}s pointing back to it; its status is
 * derived from them ({@link MissionStatus#of}), never stored.
 */
@Entity
@Table(name = "missions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA needs it; nobody else may create an empty one
public class Mission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rover_id", nullable = false, updatable = false)
    private long roverId;

    @Column(nullable = false, length = 100, updatable = false)
    private String name;

    @Column(name = "created_by", nullable = false, length = 64, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** {@link MissionResponse.Forecast} as JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String forecast;

    @Version
    @Column(nullable = false)
    private long version;

    Mission(long roverId, String name, String createdBy, Instant createdAt, String forecastJson) {
        this.roverId = roverId;
        this.name = name;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.forecast = forecastJson;
    }
}
