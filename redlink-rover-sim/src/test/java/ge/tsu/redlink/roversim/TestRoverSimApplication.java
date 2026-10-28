package ge.tsu.redlink.roversim;

import org.springframework.boot.SpringApplication;

/**
 * Runs rover-sim against a throwaway Testcontainers Postgres, no docker-compose needed.
 */
public class TestRoverSimApplication {

    public static void main(String[] args) {
        SpringApplication.from(RoverSimApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
