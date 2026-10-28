package ge.tsu.redlink.roversim.uplink;

import ge.tsu.redlink.contract.command.CommandPacket;
import ge.tsu.redlink.roversim.uplink.UplinkService.UplinkReceipt;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The rover's antenna. Only signed link packets reach it (see {@code RoverSecurityConfig}).
 */
@RestController
@RequestMapping("/flight")
@RequiredArgsConstructor
public class UplinkController {

    private final UplinkService uplinkService;

    /**
     * {@code 202 Accepted}: the command is stored, not executed. Its outcome reaches Earth later
     * as telemetry. A repeated delivery gets the same 202 with {@code duplicate: true}.
     */
    @PostMapping(path = "/uplink", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<UplinkReceipt> uplink(@Valid @RequestBody CommandPacket packet) {
        return ResponseEntity.accepted().body(uplinkService.receive(packet));
    }
}
