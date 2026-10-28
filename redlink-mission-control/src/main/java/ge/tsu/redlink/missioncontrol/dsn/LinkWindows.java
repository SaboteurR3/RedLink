package ge.tsu.redlink.missioncontrol.dsn;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * The relay-orbiter pass schedule. Windows repeat from the Unix epoch, so every instance of
 * mission control computes the same schedule from the clock alone, without coordinating.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LinkWindows {

    /**
     * @return whether a window is open at {@code now} and when that changes; always open (and
     *         never changing) when windows are disabled
     */
    public static Window at(DsnProperties.Windows windows, Instant now) {
        if (!windows.enabled()) {
            return new Window(true, null);
        }
        long every = windows.every().toMillis();
        long duration = windows.duration().toMillis();
        long millis = now.toEpochMilli();
        long cycleStart = Math.floorDiv(millis, every) * every;
        long intoCycle = millis - cycleStart;
        return intoCycle < duration
                ? new Window(true, Instant.ofEpochMilli(cycleStart + duration))
                : new Window(false, Instant.ofEpochMilli(cycleStart + every));
    }

    /** @param nextChangeAt when it closes (if open) or opens (if closed); null if it never changes */
    public record Window(boolean open, Instant nextChangeAt) {
    }
}
