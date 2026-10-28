package ge.tsu.redlink.contract.link;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * W3C Trace Context (version 00) as it travels inside link packets, so one trace follows a command
 * from the operator's request to the rover and its telemetry back to Earth. Inside the packet, not
 * only in an HTTP header: the packet is what is stored and forwarded later, and it is signed.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TraceContext {

    /** {@code version-traceid-parentid-flags}, lowercase hex; all-zero ids are invalid. */
    public static final String TRACEPARENT_PATTERN =
            "00-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}";

    /** The trace id part of a valid traceparent. */
    public static String traceId(String traceparent) {
        if (traceparent == null || !traceparent.matches(TRACEPARENT_PATTERN)) {
            throw new IllegalArgumentException("Not a W3C traceparent: " + traceparent);
        }
        return traceparent.substring(3, 35);
    }
}
