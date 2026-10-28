package ge.tsu.redlink.contract.terrain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static ge.tsu.redlink.contract.terrain.TerrainGenerator.JEZERO_SIM;
import static ge.tsu.redlink.contract.terrain.TerrainGenerator.JEZERO_SIM_SEED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TerrainLoaderTest {

    private static final Terrain GENERATED = TerrainGenerator.generate(JEZERO_SIM, JEZERO_SIM_SEED);

    @TempDir
    Path dir;

    @Test
    void writeThenLoadIsExact() {
        TerrainWriter.write(GENERATED, dir);

        assertThat(TerrainLoader.fromDirectory(dir)).isEqualTo(GENERATED);
    }

    @Test
    void shippedDefaultSiteMatchesTheGenerator() {
        // Fails if someone edits the committed files or the generator without regenerating.
        assertThat(TerrainLoader.fromClasspath("jezero-sim")).isEqualTo(GENERATED);
    }

    @Test
    void unknownClasspathSiteIsRejected() {
        assertThatThrownBy(() -> TerrainLoader.fromClasspath("olympus-mons"))
                .isInstanceOf(InvalidTerrainException.class);
    }

    @Test
    void pathTraversalInSiteIdIsRejected() {
        assertThatThrownBy(() -> TerrainLoader.fromClasspath("../../META-INF"))
                .isInstanceOf(InvalidTerrainException.class)
                .hasMessageContaining("Invalid site id");
    }

    @Test
    void imageSizeMustMatchTheGrid() throws IOException {
        TerrainWriter.write(GENERATED, dir);
        writeGray(dir.resolve("surface.png"), 32, 64, 0);

        assertThatThrownBy(() -> TerrainLoader.fromDirectory(dir))
                .isInstanceOf(InvalidTerrainException.class)
                .hasMessageContaining("surface.png is 32x64");
    }

    @Test
    void unknownSurfaceValueIsRejected() throws IOException {
        TerrainWriter.write(GENERATED, dir);
        writeGray(dir.resolve("surface.png"), 64, 64, 77);

        assertThatThrownBy(() -> TerrainLoader.fromDirectory(dir))
                .isInstanceOf(InvalidTerrainException.class)
                .hasMessageContaining("Unknown surface value 77");
    }

    @Test
    void blockedStartCellIsRejected() throws IOException {
        TerrainWriter.write(GENERATED, dir);
        writeGray(dir.resolve("surface.png"), 64, 64, SurfaceType.ROCK.pixel());

        assertThatThrownBy(() -> TerrainLoader.fromDirectory(dir))
                .isInstanceOf(InvalidTerrainException.class)
                .hasMessageContaining("Start cell is blocked");
    }

    @Test
    void colourImageIsRejected() throws IOException {
        TerrainWriter.write(GENERATED, dir);
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", dir.resolve("heightmap.png").toFile());

        assertThatThrownBy(() -> TerrainLoader.fromDirectory(dir))
                .isInstanceOf(InvalidTerrainException.class)
                .hasMessageContaining("8-bit grayscale");
    }

    @Test
    void missingPropertyIsRejected() throws IOException {
        TerrainWriter.write(GENERATED, dir);
        Path properties = dir.resolve("site.properties");
        Files.writeString(properties, Files.readString(properties).replaceAll("(?m)^startX=.*$", ""));

        assertThatThrownBy(() -> TerrainLoader.fromDirectory(dir))
                .isInstanceOf(InvalidTerrainException.class)
                .hasMessageContaining("startX");
    }

    private static void writeGray(Path file, int width, int height, int value) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.getRaster().setSample(x, y, 0, value);
            }
        }
        ImageIO.write(image, "png", file.toFile());
    }
}
