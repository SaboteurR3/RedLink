package ge.tsu.redlink.missioncontrol.command;

/**
 * Published (synchronously, inside the transaction that made the change) when a command reaches a
 * final status other than COMPLETED: failed, refused, rejected or cancelled. Missions listen to
 * abort their remaining steps; the command side does not need to know about missions.
 */
public record CommandEnded(Command command) {
}
