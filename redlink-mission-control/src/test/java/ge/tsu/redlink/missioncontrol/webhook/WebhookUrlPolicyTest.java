package ge.tsu.redlink.missioncontrol.webhook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSRF protection: user-supplied URLs must never make the server call into private networks.
 * DNS is faked, so the tests do not depend on the real internet.
 */
class WebhookUrlPolicyTest {

    private static final Map<String, String> DNS = Map.of(
            "hooks.example.com", "93.184.216.34",
            "sneaky.example.com", "127.0.0.1",          // public-looking name, loopback address
            "metadata.example.com", "169.254.169.254",  // cloud metadata service
            "intranet.example.com", "10.1.2.3",
            "v6.example.com", "2606:2800:220:1:248:1893:25c8:1946");

    private static InetAddress[] resolve(String host) throws UnknownHostException {
        String address = DNS.get(host);
        if (address != null) {
            return new InetAddress[]{InetAddress.getByName(address)};
        }
        if (Character.isDigit(host.charAt(0)) || host.contains(":")) {
            return new InetAddress[]{InetAddress.getByName(host)}; // IP literal: no DNS involved
        }
        throw new UnknownHostException(host);
    }

    private final WebhookUrlPolicy strict = new WebhookUrlPolicy(false, false, WebhookUrlPolicyTest::resolve);

    @ParameterizedTest
    @ValueSource(strings = {
            "https://hooks.example.com/redlink",
            "https://hooks.example.com:8443/a?b=c",
            "https://93.184.216.34/hook",
            "https://v6.example.com/hook"})
    void publicHttpsUrlsAreAllowed(String url) {
        assertThat(strict.violations(url)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/hook",
            "https://localhost.localdomain.invalid/hook",
            "https://sneaky.example.com/hook",
            "https://metadata.example.com/latest/meta-data/",
            "https://169.254.169.254/latest/meta-data/",
            "https://intranet.example.com/",
            "https://10.0.0.1/", "https://172.16.5.4/", "https://192.168.1.1/",
            "https://100.64.0.1/", "https://0.0.0.0/", "https://224.0.0.1/", "https://255.255.255.255/",
            "https://[::1]/", "https://[fe80::1]/", "https://[fc00::1]/", "https://[fd12:3456::1]/",
            "https://[::ffff:127.0.0.1]/", "https://[::ffff:169.254.169.254]/",
            "https://[64:ff9b::7f00:1]/", "https://[2001:db8::1]/"})
    void privateLoopbackMetadataAndUnresolvableHostsAreRefused(String url) {
        assertThat(strict.violations(url)).isNotEmpty();
    }

    @Test
    void refusalDoesNotRevealTheResolvedAddress() {
        assertThat(strict.violations("https://sneaky.example.com/")).containsExactly("url: host is not a public internet address");
    }

    @Test
    void onlyHttpsUnlessExplicitlyAllowed() {
        assertThat(strict.violations("http://hooks.example.com/")).containsExactly("url: must use https");
        assertThat(strict.violations("ftp://hooks.example.com/")).containsExactly("url: must use https");
        assertThat(new WebhookUrlPolicy(true, false, WebhookUrlPolicyTest::resolve).violations("http://hooks.example.com/")).isEmpty();
    }

    @Test
    void credentialsFragmentsAndGarbageAreRefused() {
        assertThat(strict.violations("https://user:pass@hooks.example.com/")).contains("url: must not contain credentials");
        assertThat(strict.violations("https://hooks.example.com/#x")).contains("url: must not contain a fragment");
        assertThat(strict.violations("not a url")).isNotEmpty();
        assertThat(strict.violations("https:///nohost")).isNotEmpty();
        assertThat(strict.violations("https://hooks.example.com/" + "a".repeat(2100))).isNotEmpty();
        assertThat(strict.violations("")).containsExactly("url: is required");
    }

    @Test
    void privateHostsOnlyWhenExplicitlyAllowedForDevelopment() {
        WebhookUrlPolicy dev = new WebhookUrlPolicy(true, true, WebhookUrlPolicyTest::resolve);

        assertThat(dev.violations("http://127.0.0.1:9000/hook")).isEmpty();
    }
}
