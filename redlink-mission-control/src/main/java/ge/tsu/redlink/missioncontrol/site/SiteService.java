package ge.tsu.redlink.missioncontrol.site;

import ge.tsu.redlink.contract.terrain.InvalidTerrainException;
import ge.tsu.redlink.contract.terrain.SiteInfo;
import ge.tsu.redlink.contract.terrain.TerrainFiles;
import ge.tsu.redlink.contract.terrain.Terrain;
import ge.tsu.redlink.contract.terrain.TerrainLoader;
import ge.tsu.redlink.missioncontrol.web.ApiExceptions.NotFoundException;
import ge.tsu.redlink.missioncontrol.web.ProblemTypes;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Landing sites shipped in {@code redlink-contract}: the same files the rover uses for collisions,
 * so the frontend's 3D terrain always matches the simulation. Loaded once and kept in memory.
 */
@Service
public class SiteService {

    private final Map<String, Site> sites = new ConcurrentHashMap<>();

    /**
     * @throws NotFoundException for unknown or malformed site ids
     */
    public Site get(String siteId) {
        Site site = sites.get(siteId);
        if (site == null) {
            // Load outside computeIfAbsent so failures are not cached and invalid ids never become keys.
            site = load(siteId);
            sites.putIfAbsent(siteId, site);
        }
        return site;
    }

    private static Site load(String siteId) {
        try {
            Terrain terrain = TerrainLoader.fromClasspath(siteId);
            return new Site(terrain.site(), terrain,
                    asset(siteId, TerrainFiles.HEIGHTMAP),
                    asset(siteId, TerrainFiles.SURFACE));
        } catch (InvalidTerrainException | IllegalArgumentException e) {
            throw new NotFoundException(ProblemTypes.SITE_NOT_FOUND, "Site '" + siteId + "' does not exist");
        }
    }

    private static Asset asset(String siteId, String fileName) {
        try (InputStream in = SiteService.class.getResourceAsStream(TerrainFiles.resourcePath(siteId, fileName))) {
            if (in == null) {
                throw new InvalidTerrainException("Missing " + fileName);
            }
            byte[] bytes = in.readAllBytes();
            return new Asset(bytes, contentTag(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Strong ETag derived from the content: changes exactly when the file changes. */
    private static String contentTag(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            return "\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 22) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @param terrain the map itself, for planning missions */
    public record Site(SiteInfo info, Terrain terrain, Asset heightmap, Asset surface) {
    }

    public record Asset(byte[] bytes, String etag) {
    }
}
