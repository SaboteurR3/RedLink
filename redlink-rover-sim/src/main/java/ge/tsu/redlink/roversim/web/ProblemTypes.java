package ge.tsu.redlink.roversim.web;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.net.URI;

/**
 * Stable RFC 9457 problem {@code type} URIs, same scheme as mission-control.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ProblemTypes {

    public static final String BASE = "https://redlink.example/problems/";

    public static final URI INVALID_LINK_SIGNATURE = of("invalid-link-signature");
    public static final URI PACKET_TOO_LARGE = of("packet-too-large");
    public static final URI NOT_FOUND = of("not-found");
    public static final URI INVALID_PACKET = of("invalid-packet");
    public static final URI UNKNOWN_ROVER = of("unknown-rover");
    public static final URI PACKET_CONFLICT = of("packet-conflict");
    public static final URI SEQUENCE_CONFLICT = of("sequence-conflict");
    public static final URI INTERNAL_ERROR = of("internal-error");

    public static URI of(String slug) {
        return URI.create(BASE + slug);
    }
}
