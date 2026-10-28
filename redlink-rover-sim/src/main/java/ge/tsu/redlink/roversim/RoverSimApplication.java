package ge.tsu.redlink.roversim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Mars side of RedLink: the rover "flight software". It only talks to Earth through the
 * delayed deep-space link and owns the rover's true state.
 *
 * <p>Boot's default in-memory user (with a generated password) is excluded: the rover has no
 * users, only signed link packets.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class RoverSimApplication {

    public static void main(String[] args) {
        SpringApplication.run(RoverSimApplication.class, args);
    }
}
