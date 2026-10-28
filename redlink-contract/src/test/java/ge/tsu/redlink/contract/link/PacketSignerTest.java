package ge.tsu.redlink.contract.link;

import ge.tsu.redlink.contract.link.SignatureVerificationException.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PacketSignerTest {

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_SECRET = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BODY = "{\"packetId\":\"p1\",\"command\":{\"type\":\"MOVE\",\"distanceMeters\":5}}"
            .getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    private final PacketSigner signer = signerAt(NOW, SECRET);

    @Test
    void signedPacketVerifies() {
        String header = signer.sign(BODY);

        assertThat(header).matches("t=\\d+,v1=[A-Za-z0-9_-]+");
        assertThatCode(() -> signer.verify(header, BODY)).doesNotThrowAnyException();
    }

    @Test
    void verifiesWithinToleranceInBothDirections() {
        String header = signer.sign(BODY);

        assertThatCode(() -> signerAt(NOW.plus(TOLERANCE), SECRET).verify(header, BODY)).doesNotThrowAnyException();
        assertThatCode(() -> signerAt(NOW.minus(TOLERANCE), SECRET).verify(header, BODY)).doesNotThrowAnyException();
    }

    @Test
    void tamperedBodyIsRejected() {
        String header = signer.sign(BODY);
        byte[] tampered = new String(BODY, StandardCharsets.UTF_8).replace("5", "50").getBytes(StandardCharsets.UTF_8);

        assertRejected(() -> signer.verify(header, tampered), Reason.MISMATCH);
    }

    @Test
    void wrongSecretIsRejected() {
        String header = signerAt(NOW, OTHER_SECRET).sign(BODY);

        assertRejected(() -> signer.verify(header, BODY), Reason.MISMATCH);
    }

    @Test
    void changedTimestampIsRejected() {
        String header = signer.sign(BODY).replace("t=" + NOW.getEpochSecond(), "t=" + (NOW.getEpochSecond() + 1));

        assertRejected(() -> signer.verify(header, BODY), Reason.MISMATCH);
    }

    @Test
    void expiredSignatureIsRejected() {
        String header = signer.sign(BODY);

        assertRejected(() -> signerAt(NOW.plus(TOLERANCE).plusSeconds(1), SECRET).verify(header, BODY),
                Reason.OUTSIDE_TOLERANCE);
    }

    @Test
    void signatureFromTheFutureIsRejected() {
        String header = signerAt(NOW.plus(TOLERANCE).plusSeconds(1), SECRET).sign(BODY);

        assertRejected(() -> signer.verify(header, BODY), Reason.OUTSIDE_TOLERANCE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void missingHeaderIsRejected(String header) {
        assertRejected(() -> signer.verify(header, BODY), Reason.MISSING);
        assertRejected(() -> signer.verify(null, BODY), Reason.MISSING);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "garbage",
            "t=1761480000",
            "v1=abc",
            "t=abc,v1=abc",
            "t=1761480000,v1=***",
            "t=1761480000,v1=abc,x=1",
            "t=1,t=2,v1=abc"})
    void malformedHeaderIsRejected(String header) {
        assertRejected(() -> signer.verify(header, BODY), Reason.MALFORMED);
    }

    @Test
    void shortSecretIsRefused() {
        assertThatThrownBy(() -> new PacketSigner(new byte[31], Clock.systemUTC(), TOLERANCE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PacketSigner signerAt(Instant now, byte[] secret) {
        return new PacketSigner(secret, Clock.fixed(now, ZoneOffset.UTC), TOLERANCE);
    }

    private static void assertRejected(Runnable verification, Reason reason) {
        assertThatThrownBy(verification::run)
                .isInstanceOfSatisfying(SignatureVerificationException.class,
                        e -> assertThat(e.getReason()).isEqualTo(reason));
    }
}
