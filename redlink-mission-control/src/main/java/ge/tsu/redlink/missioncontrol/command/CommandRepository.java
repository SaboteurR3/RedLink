package ge.tsu.redlink.missioncontrol.command;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface CommandRepository extends JpaRepository<Command, Long> {

    Page<Command> findByRoverId(long roverId, Pageable pageable);

    Page<Command> findByRoverIdAndStatus(long roverId, CommandStatus status, Pageable pageable);

    boolean existsByRoverIdAndStatusIn(long roverId, Collection<CommandStatus> statuses);

    List<Command> findByMissionIdInOrderByMissionIdAscMissionStepAsc(Collection<Long> missionIds);
}
