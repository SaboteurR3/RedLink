package ge.tsu.redlink.missioncontrol;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Earth side of RedLink: the public REST API operators use to command rovers.
 */
@SpringBootApplication
public class MissionControlApplication {

    public static void main(String[] args) {
        SpringApplication.run(MissionControlApplication.class, args);
    }
}
