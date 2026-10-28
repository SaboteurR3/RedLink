package ge.tsu.redlink.missioncontrol.dsn;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinkWindowsTest {

    /** Windows of 45 s every 2 min, from the Unix epoch: 12:00:00 is the start of one. */
    private static final DsnProperties.Windows WINDOWS =
            new DsnProperties.Windows(true, Duration.ofMinutes(2), Duration.ofSeconds(45));
    private static final Instant NOON = Instant.parse("2026-10-26T12:00:00Z");

    @Test
    void openAtTheStartOfACycleUntilTheDurationIsOver() {
        assertThat(LinkWindows.at(WINDOWS, NOON))
                .isEqualTo(new LinkWindows.Window(true, NOON.plusSeconds(45)));
        assertThat(LinkWindows.at(WINDOWS, NOON.plusMillis(44_999)).open()).isTrue();
    }

    @Test
    void closedForTheRestOfTheCycleAndOpensAtTheNextStart() {
        assertThat(LinkWindows.at(WINDOWS, NOON.plusSeconds(45)))
                .isEqualTo(new LinkWindows.Window(false, NOON.plusSeconds(120)));
        assertThat(LinkWindows.at(WINDOWS, NOON.plusSeconds(119)).open()).isFalse();
        assertThat(LinkWindows.at(WINDOWS, NOON.plusSeconds(120)).open()).isTrue();
    }

    @Test
    void disabledMeansAlwaysOpen() {
        DsnProperties.Windows off = new DsnProperties.Windows(false, Duration.ofMinutes(2), Duration.ofSeconds(45));

        assertThat(LinkWindows.at(off, NOON.plusSeconds(100))).isEqualTo(new LinkWindows.Window(true, null));
    }

    @Test
    void aWindowMustBeShorterThanItsCycle() {
        assertThatThrownBy(() -> new DsnProperties.Windows(true, Duration.ofMinutes(1), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
