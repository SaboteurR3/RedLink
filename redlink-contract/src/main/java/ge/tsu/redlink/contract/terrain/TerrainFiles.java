package ge.tsu.redlink.contract.terrain;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * File names inside a site folder ({@code sites/<siteId>/}) on the classpath of this module.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TerrainFiles {

    static final String SITES_ROOT = "/sites/";
    public static final String SITE_PROPERTIES = "site.properties";
    public static final String HEIGHTMAP = "heightmap.png";
    public static final String SURFACE = "surface.png";

    /**
     * Classpath location of one file of a site, for serving the raw files (e.g. to the 3D frontend).
     *
     * @throws IllegalArgumentException if the site id is not a valid slug (it could escape the folder)
     */
    public static String resourcePath(String siteId, String fileName) {
        if (siteId == null || !SiteInfo.ID_PATTERN.matcher(siteId).matches()) {
            throw new IllegalArgumentException("Invalid site id: " + siteId);
        }
        return SITES_ROOT + siteId + "/" + fileName;
    }
}
