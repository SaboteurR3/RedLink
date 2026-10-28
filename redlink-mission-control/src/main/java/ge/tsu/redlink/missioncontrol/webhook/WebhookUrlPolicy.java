package ge.tsu.redlink.missioncontrol.webhook;

import lombok.RequiredArgsConstructor;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which URLs mission-control may call. Webhook URLs come from users, so without this check anyone
 * could make the server send requests into its own network (Server-Side Request Forgery): to
 * rover-sim, to the database host, or to a cloud provider's metadata service at 169.254.169.254.
 *
 * <p>The host is resolved and <em>every</em> address must be public; a public-looking name that
 * points to 127.0.0.1 is refused. The check runs at registration and again before every delivery,
 * because DNS can change in between.
 */
@RequiredArgsConstructor
public final class WebhookUrlPolicy {

    public static final int MAX_URL_LENGTH = 2048;

    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final boolean allowHttp;
    private final boolean allowPrivateHosts;
    private final Resolver resolver;

    /**
     * @return what is wrong with the URL; empty if it may be called
     */
    public List<String> violations(String url) {
        List<String> problems = new ArrayList<>();
        if (url == null || url.isBlank()) {
            return List.of("url: is required");
        }
        if (url.length() > MAX_URL_LENGTH) {
            return List.of("url: must be at most " + MAX_URL_LENGTH + " characters");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return List.of("url: is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(allowHttp && scheme.equals("http"))) {
            problems.add("url: must use https");
        }
        if (uri.getRawUserInfo() != null) {
            problems.add("url: must not contain credentials");
        }
        if (uri.getRawFragment() != null) {
            problems.add("url: must not contain a fragment");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            problems.add("url: must have a host");
            return problems;
        }
        if (!allowPrivateHosts) {
            problems.addAll(addressViolations(host));
        }
        return problems;
    }

    private List<String> addressViolations(String host) {
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(bare);
        } catch (UnknownHostException e) {
            return List.of("url: host does not resolve");
        }
        if (addresses.length == 0) {
            return List.of("url: host does not resolve");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                // Don't echo the resolved address: that would turn the API into a DNS oracle.
                return List.of("url: host is not a public internet address");
            }
        }
        return List.of();
    }

    /** True only for globally routable unicast addresses. */
    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            return !(first == 0                                       // "this" network
                    || first == 10                                    // private
                    || first == 100 && second >= 64 && second <= 127  // carrier-grade NAT
                    || first == 127                                   // loopback
                    || first == 169 && second == 254                  // link-local, cloud metadata
                    || first == 172 && second >= 16 && second <= 31   // private
                    || first == 192 && second == 0 && (b[2] & 0xFF) == 0   // IETF protocol assignments
                    || first == 192 && second == 0 && (b[2] & 0xFF) == 2   // documentation
                    || first == 192 && second == 168                  // private
                    || first == 198 && (second == 18 || second == 19) // benchmarking
                    || first == 198 && second == 51 && (b[2] & 0xFF) == 100 // documentation
                    || first == 203 && second == 0 && (b[2] & 0xFF) == 113  // documentation
                    || first >= 224);                                 // multicast, reserved, broadcast
        }
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            boolean uniqueLocal = (first & 0xFE) == 0xFC;                       // fc00::/7
            boolean documentation = first == 0x20 && second == 0x01 && (b[2] & 0xFF) == 0x0D && (b[3] & 0xFF) == 0xB8;
            boolean nat64 = first == 0x00 && second == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B;
            boolean ipv4Compatible = isZero(b, 0, 12);                          // ::a.b.c.d (deprecated)
            return !(uniqueLocal || documentation || nat64 || ipv4Compatible);
        }
        return false;
    }

    private static boolean isZero(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
