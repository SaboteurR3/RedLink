package ge.tsu.redlink.roversim.downlink;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DownlinkPacketRepository extends JpaRepository<DownlinkPacket, UUID> {
}
