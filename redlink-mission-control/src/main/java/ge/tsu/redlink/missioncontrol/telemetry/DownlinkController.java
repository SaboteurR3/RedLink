package ge.tsu.redlink.missioncontrol.telemetry;

import ge.tsu.redlink.contract.telemetry.TelemetryPacket;
import ge.tsu.redlink.missioncontrol.dsn.DsnService;
import ge.tsu.redlink.missioncontrol.telemetry.TelemetryService.DownlinkReceipt;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Deep Space Network's receiving end. Only signed link packets reach it (see
 * {@code LinkSecurityConfig}); it is machine-to-machine and not part of the public API documentation.
 */
@RestController
@RequestMapping("/dsn")
@RequiredArgsConstructor
class DownlinkController {

    private final TelemetryService telemetry;
    private final DsnService dsn;

    /**
     * {@code 202}, also for a repeated delivery ({@code duplicate: true}), so the sender stops
     * retrying either way.
     * During a solar conjunction: {@code 503} with {@code Retry-After}; the rover keeps the packet.
     */
    @PostMapping(path = "/downlink", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<DownlinkReceipt> downlink(@Valid @RequestBody TelemetryPacket packet) {
        dsn.requireDownlink();
        return ResponseEntity.accepted().body(telemetry.receive(packet));
    }
}
