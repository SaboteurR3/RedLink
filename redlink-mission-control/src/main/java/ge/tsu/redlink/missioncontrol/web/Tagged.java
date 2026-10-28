package ge.tsu.redlink.missioncontrol.web;

/**
 * A representation together with its entity tag, so controllers can set {@code ETag}.
 */
public record Tagged<T>(T body, String etag) {
}
