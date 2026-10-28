package ge.tsu.redlink.contract.rover;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/**
 * Time of day at the landing site. A sol is compressed so a demo sees dawn, noon, dusk and night,
 * and the phase is a pure function of the time, so the rover, Earth and every browser agree on it
 * without talking to each other (the browser's sky uses the same formula).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MarsClock {

    /** One sol, midnight to midnight. */
    public static final Duration SOL = Duration.ofMinutes(12);

    /** 0 = midnight, 0.25 = sunrise, 0.5 = noon, 0.75 = sunset. */
    public static double phase(Instant time) {
        long sol = SOL.toMillis();
        return Math.floorMod(time.toEpochMilli(), sol) / (double) sol;
    }

    /**
     * What the solar panels get, as a fraction of full power: the sun's height, 1 at noon, lower in
     * the morning and evening, 0 from sunset to sunrise.
     */
    public static double sunlight(Instant time) {
        return Math.max(0, Math.sin((phase(time) - 0.25) * 2 * Math.PI));
    }

    /** How long until the sun rises; zero while it is up. */
    public static Duration untilSunrise(Instant time) {
        double phase = phase(time);
        if (phase >= 0.25 && phase < 0.75) {
            return Duration.ZERO;
        }
        double left = phase < 0.25 ? 0.25 - phase : 1.25 - phase;
        return Duration.ofMillis(Math.round(left * SOL.toMillis()));
    }
}
