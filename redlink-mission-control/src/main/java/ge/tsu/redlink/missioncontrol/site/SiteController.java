package ge.tsu.redlink.missioncontrol.site;

import ge.tsu.redlink.contract.terrain.SiteInfo;
import ge.tsu.redlink.missioncontrol.site.SiteService.Asset;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Terrain of the landing sites. The files never change while the app runs, so clients may cache
 * them for a day and revalidate cheaply with If-None-Match (content-hash ETags).
 */
@RestController
@RequestMapping("/api/v1/sites")
@Tag(name = "Sites", description = "Landing-site terrain, the same files the rover simulation uses")
@PreAuthorize("hasRole('VIEWER')")
@RequiredArgsConstructor
public class SiteController {

    private static final CacheControl ONE_DAY = CacheControl.maxAge(Duration.ofDays(1)).cachePrivate();

    private final SiteService sites;

    @Operation(summary = "Site metadata: grid size, elevation range, landing cell")
    @GetMapping("/{siteId}")
    ResponseEntity<SiteResponse> site(@PathVariable String siteId) {
        SiteService.Site site = sites.get(siteId);
        return ResponseEntity.ok().cacheControl(ONE_DAY).eTag(site.heightmap().etag())
                .body(SiteResponse.of(site.info()));
    }

    @Operation(summary = "Heightmap: 8-bit grayscale PNG, 0 = lowest, 255 = highest elevation")
    @GetMapping(path = "/{siteId}/heightmap", produces = MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<byte[]> heightmap(@PathVariable String siteId) {
        return png(sites.get(siteId).heightmap());
    }

    @Operation(summary = "Surface map: 8-bit grayscale PNG, 0 = ground, 128 = sand, 255 = rock")
    @GetMapping(path = "/{siteId}/surface", produces = MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<byte[]> surface(@PathVariable String siteId) {
        return png(sites.get(siteId).surface());
    }

    private static ResponseEntity<byte[]> png(Asset asset) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(ONE_DAY).eTag(asset.etag())
                .body(asset.bytes());
    }

    /**
     * @param metersPerCell size of one grid cell (and one rover step)
     */
    public record SiteResponse(String id, String name, int width, int height, double metersPerCell,
                               double minElevationMeters, double maxElevationMeters, Cell start) {

        static SiteResponse of(SiteInfo info) {
            return new SiteResponse(info.id(), info.name(), info.width(), info.height(), info.metersPerCell(),
                    info.minElevationMeters(), info.maxElevationMeters(), new Cell(info.startX(), info.startY()));
        }
    }

    public record Cell(int x, int y) {
    }
}
