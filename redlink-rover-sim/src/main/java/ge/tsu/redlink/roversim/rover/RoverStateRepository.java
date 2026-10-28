package ge.tsu.redlink.roversim.rover;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RoverStateRepository extends JpaRepository<RoverState, Long> {

    @Query("SELECT r.roverId FROM RoverState r ORDER BY r.roverId")
    List<Long> findAllIds();
}
