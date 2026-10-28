package ge.tsu.redlink.missioncontrol.webhook;

import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/**
 * What a webhook can subscribe to. The wire name ({@code command.completed}) is what clients use.
 */
@RequiredArgsConstructor
public enum WebhookEvent {
    COMMAND_COMPLETED("command.completed"),
    /** Failed on Mars, refused by the rover, or rejected on the uplink. */
    COMMAND_FAILED("command.failed"),
    /** The rover entered safe mode (not repeated while it stays there). */
    ROVER_SAFE_MODE("rover.safe_mode"),
    /** The battery fell below {@link #LOW_BATTERY_PERCENT} (not repeated while it stays below). */
    ROVER_BATTERY_LOW("rover.battery_low"),
    /** A test delivery, sent on request; every webhook receives it. */
    PING("ping");

    public static final double LOW_BATTERY_PERCENT = 20.0;

    private final String wireName;

    public String wireName() {
        return wireName;
    }

    public static Optional<WebhookEvent> fromWireName(String name) {
        return Arrays.stream(values()).filter(event -> event.wireName.equals(name)).findFirst();
    }
}
