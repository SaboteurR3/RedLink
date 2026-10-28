package ge.tsu.redlink.missioncontrol.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    /** @param username already normalized with {@link UserAccount#normalizeUsername(String)} */
    Optional<UserAccount> findByUsername(String username);

    boolean existsByUsername(String username);
}
