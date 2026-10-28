package ge.tsu.redlink.missioncontrol.photo;

/**
 * The PNG of a photo with its hash (the strong entity tag of the image representation).
 */
public record PhotoImage(String sha256, byte[] png) {
}
