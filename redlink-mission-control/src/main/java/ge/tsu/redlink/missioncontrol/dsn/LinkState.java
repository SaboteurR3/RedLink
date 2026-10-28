package ge.tsu.redlink.missioncontrol.dsn;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** The single row of operator-controlled link state. */
@Entity
@Table(name = "link_state")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA needs it; nobody else may create an empty one
public class LinkState {

    static final short ID = 1;

    @Id
    private Short id;

    @Column(name = "conjunction_active", nullable = false)
    private boolean conjunctionActive;

    @Column(name = "changed_by", length = 64)
    private String changedBy;

    @Column(name = "changed_at")
    private Instant changedAt;

    @Version
    @Column(nullable = false)
    private long version;

    /**
     * @return false if it already was so: setting the same value again changes nothing (idempotent PUT)
     */
    boolean setConjunction(boolean active, String user, Instant now) {
        if (active == conjunctionActive) {
            return false;
        }
        this.conjunctionActive = active;
        this.changedBy = user;
        this.changedAt = now;
        return true;
    }
}
