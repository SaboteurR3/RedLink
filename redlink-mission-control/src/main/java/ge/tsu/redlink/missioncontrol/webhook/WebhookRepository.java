package ge.tsu.redlink.missioncontrol.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookRepository extends JpaRepository<Webhook, UUID> {

    List<Webhook> findByOwnerOrderByCreatedAtAsc(String owner);

    /** Ownership is part of the lookup: someone else's webhook is simply "not found". */
    Optional<Webhook> findByIdAndOwner(UUID id, String owner);

    long countByOwner(String owner);
}
