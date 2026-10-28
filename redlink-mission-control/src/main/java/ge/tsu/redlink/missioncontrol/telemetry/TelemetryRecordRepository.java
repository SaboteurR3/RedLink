package ge.tsu.redlink.missioncontrol.telemetry;

import ge.tsu.redlink.contract.telemetry.TelemetryType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;

public interface TelemetryRecordRepository extends JpaRepository<TelemetryRecord, Long>,
        JpaSpecificationExecutor<TelemetryRecord> {

    /** Building blocks for the history filters; each is only added when its parameter is given. */
    final class Filters {

        private Filters() {
        }

        static Specification<TelemetryRecord> ofRover(long roverId) {
            return (root, query, cb) -> cb.equal(root.get("roverId"), roverId);
        }

        static Specification<TelemetryRecord> ofType(TelemetryType type) {
            return (root, query, cb) -> cb.equal(root.get("type"), type);
        }

        /** Inclusive lower bound on Mars time. */
        static Specification<TelemetryRecord> from(Instant from) {
            return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("roverTime"), from);
        }

        /** Exclusive upper bound on Mars time, so consecutive ranges never overlap. */
        static Specification<TelemetryRecord> until(Instant to) {
            return (root, query, cb) -> cb.lessThan(root.get("roverTime"), to);
        }
    }
}
