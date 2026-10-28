package ge.tsu.redlink.roversim.flight;

import ge.tsu.redlink.contract.terrain.Terrain;
import ge.tsu.redlink.contract.terrain.TerrainLoader;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads each landing site once. {@link Terrain} is immutable, so one instance serves every thread.
 */
@Component
public class TerrainRegistry {

    private final Map<String, Terrain> sites = new ConcurrentHashMap<>();

    public Terrain get(String siteId) {
        return sites.computeIfAbsent(siteId, TerrainLoader::fromClasspath);
    }
}
