package ge.tsu.redlink.contract.rover;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MarsClockTest {

    /** A sol boundary: the epoch is midnight, and so is every whole number of sols after it. */
    private static final Instant MIDNIGHT = Instant.EPOCH.plus(MarsClock.SOL.multipliedBy(1000));

    private static Instant at(double phase) {
        return MIDNIGHT.plusMillis(Math.round(phase * MarsClock.SOL.toMillis()));
    }

    @Test
    void phaseWrapsEverySol() {
        assertThat(MarsClock.phase(MIDNIGHT)).isZero();
        assertThat(MarsClock.phase(at(0.5))).isEqualTo(0.5);
        assertThat(MarsClock.phase(at(0.25).plus(MarsClock.SOL.multipliedBy(3)))).isEqualTo(0.25);
        assertThat(MarsClock.phase(Instant.EPOCH.minusMillis(MarsClock.SOL.toMillis() / 4))).isEqualTo(0.75);
    }

    @Test
    void sunlightIsFullAtNoonPartialInTheMorningAndNoneAtNight() {
        assertThat(MarsClock.sunlight(at(0.5))).isCloseTo(1, within(1e-9));
        assertThat(MarsClock.sunlight(at(0.25 + 1.0 / 12))).isCloseTo(0.5, within(1e-6)); // 30° above the horizon
        assertThat(MarsClock.sunlight(at(0.25))).isCloseTo(0, within(1e-9));
        assertThat(MarsClock.sunlight(at(0.75))).isCloseTo(0, within(1e-9));
        assertThat(MarsClock.sunlight(at(0.9))).isZero();
        assertThat(MarsClock.sunlight(MIDNIGHT)).isZero();
    }

    @Test
    void untilSunriseCountsDownAtNightAndIsZeroByDay() {
        assertThat(MarsClock.untilSunrise(MIDNIGHT)).isEqualTo(MarsClock.SOL.dividedBy(4));
        assertThat(MarsClock.untilSunrise(at(0.75))).isEqualTo(MarsClock.SOL.dividedBy(2));
        assertThat(MarsClock.untilSunrise(at(0.5))).isEqualTo(Duration.ZERO);
        assertThat(MarsClock.untilSunrise(at(0.25))).isEqualTo(Duration.ZERO);
    }
}
