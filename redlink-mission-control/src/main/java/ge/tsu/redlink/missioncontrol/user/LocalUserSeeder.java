package ge.tsu.redlink.missioncontrol.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.Clock;

/**
 * Creates one user per role: {@code viewer}, {@code operator} and {@code director}. Exists only
 * under the {@code local} profile (the dev password from {@code application-local.yml}) and the
 * {@code demo} profile (Docker Compose; the password must come from the environment, there is no
 * default), so no known password can reach a shared environment.
 */
@Component
@Profile({"local", "demo"})
@EnableConfigurationProperties(LocalUserSeeder.DevUsersProperties.class)
@Slf4j
@RequiredArgsConstructor
class LocalUserSeeder implements ApplicationRunner {

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final DevUsersProperties properties;
    private final Clock clock;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed("viewer", Role.VIEWER);
        seed("operator", Role.OPERATOR);
        seed("director", Role.FLIGHT_DIRECTOR);
    }

    private void seed(String username, Role role) {
        if (!users.existsByUsername(username)) {
            users.save(new UserAccount(username, passwordEncoder.encode(properties.password()), role, clock.instant()));
            log.info("Created local dev user '{}' with role {}", username, role);
        }
    }

    @Validated
    @ConfigurationProperties("redlink.dev-users")
    record DevUsersProperties(@NotBlank @Size(min = 8) String password) {
    }
}
