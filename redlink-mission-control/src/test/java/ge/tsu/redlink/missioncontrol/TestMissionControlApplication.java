package ge.tsu.redlink.missioncontrol;

import org.springframework.boot.SpringApplication;

/**
 * Runs mission-control against a throwaway Testcontainers Postgres, no docker-compose needed.
 */
public class TestMissionControlApplication {

    public static void main(String[] args) {
        SpringApplication.from(MissionControlApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
