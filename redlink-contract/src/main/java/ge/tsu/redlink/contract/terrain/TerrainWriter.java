package ge.tsu.redlink.contract.terrain;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntBinaryOperator;

import static ge.tsu.redlink.contract.terrain.TerrainFiles.HEIGHTMAP;
import static ge.tsu.redlink.contract.terrain.TerrainFiles.SITE_PROPERTIES;
import static ge.tsu.redlink.contract.terrain.TerrainFiles.SURFACE;

/**
 * Writes a site folder that {@link TerrainLoader#fromDirectory(Path)} reads back unchanged.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TerrainWriter {

    public static void write(Terrain terrain, Path directory) {
        try {
            Files.createDirectories(directory);
            writeProperties(terrain.site(), directory.resolve(SITE_PROPERTIES));
            writeGray(terrain, terrain::levelAt, directory.resolve(HEIGHTMAP));
            writeGray(terrain, (x, y) -> terrain.surfaceAt(x, y).pixel(), directory.resolve(SURFACE));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write site to " + directory, e);
        }
    }

    // Written by hand rather than with Properties.store, which adds a timestamp and reorders keys:
    // the file is committed, so regenerating the same site must give the same bytes.
    private static void writeProperties(SiteInfo site, Path file) throws IOException {
        Files.write(file, List.of(
                "# RedLink landing site. Images: heightmap.png (0 = minElevationMeters, 255 = maxElevationMeters),",
                "# surface.png (0 = ground, 128 = sand, 255 = rock). Row 0 is the north edge.",
                "id=" + site.id(),
                "name=" + site.name(),
                "width=" + site.width(),
                "height=" + site.height(),
                "metersPerCell=" + site.metersPerCell(),
                "minElevationMeters=" + site.minElevationMeters(),
                "maxElevationMeters=" + site.maxElevationMeters(),
                "startX=" + site.startX(),
                "startY=" + site.startY()), StandardCharsets.UTF_8);
    }

    private static void writeGray(Terrain terrain, IntBinaryOperator valueAt, Path file) throws IOException {
        BufferedImage image = new BufferedImage(terrain.width(), terrain.height(), BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < terrain.height(); y++) {
            for (int x = 0; x < terrain.width(); x++) {
                image.getRaster().setSample(x, y, 0, valueAt.applyAsInt(x, y));
            }
        }
        if (!ImageIO.write(image, "png", file.toFile())) {
            throw new IOException("No PNG writer available");
        }
    }
}
