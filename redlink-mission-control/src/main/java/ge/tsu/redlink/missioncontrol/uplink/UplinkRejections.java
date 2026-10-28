package ge.tsu.redlink.missioncontrol.uplink;

import ge.tsu.redlink.missioncontrol.command.CommandEnded;
import ge.tsu.redlink.missioncontrol.command.CommandRepository;
import ge.tsu.redlink.missioncontrol.command.CommandResponse;
import ge.tsu.redlink.missioncontrol.events.EventType;
import ge.tsu.redlink.missioncontrol.events.RoverEventPublisher;
import ge.tsu.redlink.missioncontrol.uplink.UplinkClaims.ClaimedPacket;
import ge.tsu.redlink.missioncontrol.webhook.WebhookEvent;
import ge.tsu.redlink.missioncontrol.webhook.WebhookNotifier;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

/**
 * A packet the rover refused for good: the packet and its command fail together, in one transaction.
 */
@Service
@RequiredArgsConstructor
class UplinkRejections {

    private final UplinkClaims claims;
    private final CommandRepository commands;
    private final RoverEventPublisher events;
    private final WebhookNotifier webhooks;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher ended;

    @Transactional
    public void reject(ClaimedPacket packet, String detail, Instant now) {
        if (claims.markRejected(packet.packetId(), detail)) {
            commands.findById(packet.commandId()).ifPresent(command -> {
                command.uplinkRejected("The rover refused the uplink packet (" + detail + ")", now);
                CommandResponse view = CommandResponse.of(command, jsonMapper);
                events.publish(command.getRoverId(), EventType.COMMAND, view);
                webhooks.notify(WebhookEvent.COMMAND_FAILED, view);
                ended.publishEvent(new CommandEnded(command));
            });
        }
    }
}
