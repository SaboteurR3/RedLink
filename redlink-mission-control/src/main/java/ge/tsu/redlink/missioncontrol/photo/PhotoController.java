package ge.tsu.redlink.missioncontrol.photo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

/**
 * Photos are one resource with two representations, chosen by {@code Accept} (RFC 9110 §12):
 * JSON metadata or the PNG. Both answers say {@code Vary: Accept} so caches keep them apart, and
 * each has its own strong entity tag.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Photos", description = "Images taken by TAKE_PHOTO; JSON or PNG by content negotiation")
@RequiredArgsConstructor
public class PhotoController {

    /** The image never changes: any cache may keep it for a year without asking again. */
    private static final CacheControl IMMUTABLE = CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable();
    /** The JSON may gain fields in a new release, so it is revalidated (cheaply, with If-None-Match). */
    private static final CacheControl REVALIDATE = CacheControl.noCache().cachePrivate();

    private final PhotoService photos;

    @Operation(summary = "Photos of a rover", description = "Metadata only, newest first by Mars time.")
    @GetMapping("/rovers/{roverId}/photos")
    @PreAuthorize("hasRole('VIEWER')")
    PagedModel<PhotoResponse> list(@PathVariable long roverId,
                                   @RequestParam(defaultValue = "0") @Min(0) int page,
                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Sort newestFirst = Sort.by(Sort.Order.desc("takenAt"), Sort.Order.desc("id"));
        return new PagedModel<>(photos.list(roverId, PageRequest.of(page, size, newestFirst)));
    }

    @Operation(summary = "Get a photo",
            description = "Accept: application/json (default) → metadata; Accept: image/png → the image; "
                    + "anything else → 406. Supports If-None-Match → 304.")
    @GetMapping(path = "/photos/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('VIEWER')")
    ResponseEntity<PhotoResponse> metadata(@PathVariable UUID id) {
        PhotoMetadata photo = photos.metadata(id);
        return ResponseEntity.ok()
                .eTag(photo.sha256().substring(0, 32) + "-json")
                .cacheControl(REVALIDATE)
                .varyBy(HttpHeaders.ACCEPT)
                .body(PhotoResponse.of(photo));
    }

    @Operation(summary = "Get a photo", description = "The PNG representation; see the JSON one.")
    @GetMapping(path = "/photos/{id}", produces = MediaType.IMAGE_PNG_VALUE)
    @PreAuthorize("hasRole('VIEWER')")
    ResponseEntity<byte[]> image(@PathVariable UUID id) {
        PhotoImage image = photos.image(id);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .eTag(image.sha256().substring(0, 32))
                .cacheControl(IMMUTABLE)
                .varyBy(HttpHeaders.ACCEPT)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename("photo-" + id + ".png").build().toString())
                .body(image.png());
    }
}
