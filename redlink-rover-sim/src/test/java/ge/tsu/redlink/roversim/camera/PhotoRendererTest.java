package ge.tsu.redlink.roversim.camera;

import ge.tsu.redlink.contract.rover.Camera;
import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.terrain.Terrain;
import ge.tsu.redlink.contract.terrain.TerrainLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoRendererTest {

    private static final Terrain JEZERO = TerrainLoader.fromClasspath("jezero-sim");

    @ParameterizedTest
    @EnumSource(Camera.class)
    void rendersAPngOfTheCameraSize(Camera camera) throws IOException {
        byte[] png = PhotoRenderer.render(JEZERO, 32, 32, Heading.NORTH, camera);

        assertThat(png).startsWith(0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n');
        BufferedImage image = decode(png);
        assertThat(image.getWidth()).isEqualTo(PhotoRenderer.WIDTH);
        assertThat(image.getHeight()).isEqualTo(PhotoRenderer.HEIGHT);
    }

    @Test
    void sameViewGivesTheSameBytes() {
        assertThat(PhotoRenderer.render(JEZERO, 20, 40, Heading.EAST, Camera.MASTCAM))
                .isEqualTo(PhotoRenderer.render(JEZERO, 20, 40, Heading.EAST, Camera.MASTCAM));
    }

    @Test
    void differentHeadingsSeeDifferentThings() {
        assertThat(PhotoRenderer.render(JEZERO, 32, 32, Heading.NORTH, Camera.MASTCAM))
                .isNotEqualTo(PhotoRenderer.render(JEZERO, 32, 32, Heading.SOUTH, Camera.MASTCAM));
    }

    @Test
    void navcamIsGrayscaleAndMastcamIsColour() throws IOException {
        assertThat(isGray(decode(PhotoRenderer.render(JEZERO, 32, 32, Heading.WEST, Camera.NAVCAM)))).isTrue();
        assertThat(isGray(decode(PhotoRenderer.render(JEZERO, 32, 32, Heading.WEST, Camera.MASTCAM)))).isFalse();
    }

    @Test
    void skyIsAboveTheGround() throws IOException {
        BufferedImage image = decode(PhotoRenderer.render(JEZERO, 32, 32, Heading.NORTH, Camera.MASTCAM));

        int sky = image.getRGB(PhotoRenderer.WIDTH / 2, 30);
        int ground = image.getRGB(PhotoRenderer.WIDTH / 2, PhotoRenderer.HEIGHT - 30);
        assertThat(sky).isNotEqualTo(ground);
    }

    private static BufferedImage decode(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image).isNotNull();
        return image;
    }

    private static boolean isGray(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r != g || g != b) {
                    return false;
                }
            }
        }
        return true;
    }
}
