package ge.tsu.redlink.contract.terrain;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Properties;

import static ge.tsu.redlink.contract.terrain.TerrainFiles.HEIGHTMAP;
import static ge.tsu.redlink.contract.terrain.TerrainFiles.SITES_ROOT;
import static ge.tsu.redlink.contract.terrain.TerrainFiles.SITE_PROPERTIES;
import static ge.tsu.redlink.contract.terrain.TerrainFiles.SURFACE;

/**
 * Reads and validates a site folder: {@code site.properties}, {@code heightmap.png} and
 * {@code surface.png}. Both images must be 8-bit grayscale and exactly the grid size.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TerrainLoader {

    /**
     * Loads {@code sites/<siteId>/} from the classpath (shipped inside redlink-contract).
     */
    public static Terrain fromClasspath(String siteId) {
        if (siteId == null || !SiteInfo.ID_PATTERN.matcher(siteId).matches()) {
            throw new InvalidTerrainException("Invalid site id: " + siteId);
        }
        return load("classpath site " + siteId, fileName -> {
            InputStream in = TerrainLoader.class.getResourceAsStream(SITES_ROOT + siteId + "/" + fileName);
            if (in == null) {
                throw new NoSuchFileException(fileName);
            }
            return in;
        });
    }

    public static Terrain fromDirectory(Path directory) {
        return load("site folder " + directory, fileName -> Files.newInputStream(directory.resolve(fileName)));
    }

    private static Terrain load(String source, FileOpener opener) {
        try {
            SiteInfo site = readSite(opener);
            int[] levels = readGray(opener, HEIGHTMAP, site);
            int[] surfacePixels = readGray(opener, SURFACE, site);

            SurfaceType[] surface = new SurfaceType[surfacePixels.length];
            for (int i = 0; i < surfacePixels.length; i++) {
                surface[i] = SurfaceType.fromPixel(surfacePixels[i]);
            }
            return new Terrain(site, levels, surface);
        } catch (IOException | IllegalArgumentException e) {
            throw new InvalidTerrainException("Cannot load " + source + ": " + e.getMessage(), e);
        }
    }

    private static SiteInfo readSite(FileOpener opener) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(opener.open(SITE_PROPERTIES), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return new SiteInfo(
                required(properties, "id"),
                required(properties, "name"),
                Integer.parseInt(required(properties, "width")),
                Integer.parseInt(required(properties, "height")),
                Double.parseDouble(required(properties, "metersPerCell")),
                Double.parseDouble(required(properties, "minElevationMeters")),
                Double.parseDouble(required(properties, "maxElevationMeters")),
                Integer.parseInt(required(properties, "startX")),
                Integer.parseInt(required(properties, "startY")));
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(SITE_PROPERTIES + " is missing '" + key + "'");
        }
        return value.trim();
    }

    private static int[] readGray(FileOpener opener, String fileName, SiteInfo site) throws IOException {
        BufferedImage image;
        try (InputStream in = opener.open(fileName)) {
            image = ImageIO.read(in);
        }
        if (image == null) {
            throw new IllegalArgumentException(fileName + " is not a readable image");
        }
        if (image.getType() != BufferedImage.TYPE_BYTE_GRAY) {
            throw new IllegalArgumentException(fileName + " must be an 8-bit grayscale PNG");
        }
        if (image.getWidth() != site.width() || image.getHeight() != site.height()) {
            throw new IllegalArgumentException(fileName + " is " + image.getWidth() + "x" + image.getHeight()
                    + ", expected " + site.width() + "x" + site.height());
        }
        Raster raster = image.getRaster();
        return raster.getSamples(0, 0, site.width(), site.height(), 0, new int[site.width() * site.height()]);
    }

    @FunctionalInterface
    private interface FileOpener {
        InputStream open(String fileName) throws IOException;
    }
}
