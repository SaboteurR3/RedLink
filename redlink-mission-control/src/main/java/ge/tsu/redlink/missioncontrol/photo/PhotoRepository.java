package ge.tsu.redlink.missioncontrol.photo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface PhotoRepository extends JpaRepository<Photo, UUID> {

    Page<PhotoMetadata> findByRoverId(long roverId, Pageable pageable);

    Optional<PhotoMetadata> findMetadataById(UUID id);

    @Query("SELECT new ge.tsu.redlink.missioncontrol.photo.PhotoImage(p.sha256, p.image) FROM Photo p WHERE p.id = :id")
    Optional<PhotoImage> findImageById(UUID id);
}
