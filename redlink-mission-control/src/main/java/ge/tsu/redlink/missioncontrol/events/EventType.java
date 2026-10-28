package ge.tsu.redlink.missioncontrol.events;

import lombok.RequiredArgsConstructor;

/**
 * SSE event names ({@code event:} field). Clients subscribe with
 * {@code eventSource.addEventListener("command", ...)}.
 */
@RequiredArgsConstructor
public enum EventType {
    /** The rover ({@code RoverResponse}): snapshot on connect, then every change. */
    ROVER("rover"),
    /** A command ({@code CommandResponse}) whenever it is created or its status changes. */
    COMMAND("command"),
    /** A command left Earth: {@link UplinkTransmission}, for the signal-beam animation. */
    UPLINK("uplink"),
    /** A rover EVENT telemetry report: {@link RoverEventMessage}. */
    ROVER_EVENT("rover-event"),
    /** A photo arrived: its metadata ({@code PhotoResponse}). */
    PHOTO("photo"),
    /** The link changed (a solar conjunction began or ended): {@code LinkStatus}; sent to every rover. */
    LINK("link"),
    /** The resume point is too old; carries a fresh rover snapshot. Clients should reload. */
    RESET("reset");

    private final String wireName;

    public String wireName() {
        return wireName;
    }
}
