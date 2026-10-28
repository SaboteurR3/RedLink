package ge.tsu.redlink.roversim.events;

import ge.tsu.redlink.contract.rover.RoverSpecs;
import ge.tsu.redlink.contract.telemetry.EventSeverity;
import ge.tsu.redlink.contract.telemetry.TelemetryPayload;
import ge.tsu.redlink.roversim.downlink.TelemetryOutbox;
import ge.tsu.redlink.roversim.rover.RoverState;
import ge.tsu.redlink.roversim.rover.RoverStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Mars weather at a rover: dust storms come and go. Earth hears about each change as an EVENT,
 * in the same transaction as the change.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class Weather {

    private final RoverStateRepository rovers;
    private final TelemetryOutbox telemetry;
    private final RoverEventsProperties properties;
    private final Dice dice;
    private final Clock clock;

    /** One weather check: ends a storm that is over, or maybe starts one. */
    @Transactional
    public void update(long roverId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        RoverState rover = rovers.findById(roverId).orElseThrow(() -> new IllegalArgumentException("No rover " + roverId));
        if (rover.getDustStormUntil() != null) {
            if (!rover.inDustStorm(now)) {
                rover.endDustStorm(now);
                telemetry.emit(roverId, new TelemetryPayload.Event("DUST_STORM_ENDED", EventSeverity.INFO,
                        "The dust storm has passed; solar charging is back to normal"));
                log.info("Rover {}: dust storm ended", roverId);
            }
            return;
        }
        if (properties.enabled() && dice.chance(properties.dustStormChancePerCheck())) {
            rover.startDustStorm(now.plus(properties.dustStormDuration()), now);
            telemetry.emit(roverId, new TelemetryPayload.Event("DUST_STORM_STARTED", EventSeverity.WARNING,
                    String.format(Locale.ROOT, "Dust storm for about %d min: solar charging at %.0f%%",
                            Math.max(1, properties.dustStormDuration().toMinutes()), RoverSpecs.DUST_STORM_CHARGE_FACTOR * 100)));
            log.warn("Rover {}: dust storm until {}", roverId, rover.getDustStormUntil());
        }
    }
}
