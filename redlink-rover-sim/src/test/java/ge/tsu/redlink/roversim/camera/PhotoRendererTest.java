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
        byte[] png = PhotoRenderer.render(JEZERO, 64, 64, Heading.NORTH, camera);

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
        assertThat(PhotoRenderer.render(JEZERO, 64, 64, Heading.NORTH, Camera.MASTCAM))
                .isNotEqualTo(PhotoRenderer.render(JEZERO, 64, 64, Heading.SOUTH, Camera.MASTCAM));
    }

    @Test
    void navcamIsGrayscaleAndMastcamIsColour() throws IOException {
        assertThat(isGray(decode(PhotoRenderer.render(JEZERO, 64, 64, Heading.WEST, Camera.NAVCAM)))).isTrue();
        assertThat(isGray(decode(PhotoRenderer.render(JEZERO, 64, 64, Heading.WEST, Camera.MASTCAM)))).isFalse();
    }

    @Test
    void skyIsAboveTheGround() throws IOException {
        BufferedImage image = decode(PhotoRenderer.render(JEZERO, 64, 64, Heading.NORTH, Camera.MASTCAM));

        int sky = image.getRGB(PhotoRenderer.WIDTH / 2, 30);
        int ground = image.getRGB(PhotoRenderer.WIDTH / 2, PhotoRenderer.HEIGHT - 30);
        assertThat(sky).isNotEqualTo(ground);
    }

    @Test
    void aBoulderIsARoundedDomeNotABox() {
        // Rock cell (10, 20): heights across it, from its middle to beyond its edge, along one line.
        double middle = PhotoRenderer.boulderHeight(10, 20, 10.5, 20.5);
        double halfway = PhotoRenderer.boulderHeight(10, 20, 10.75, 20.5);
        double outside = PhotoRenderer.boulderHeight(10, 20, 11.5, 20.5);

        assertThat(middle).isBetween(0.2, 0.9);
        assertThat(halfway).as("lower towards the edge").isLessThan(middle).isPositive();
        assertThat(outside).as("nothing a cell away").isZero();
    }

    @Test
    void aBouldersOutlineIsIrregular() {
        // Where the outline is crossed depends on the direction: lumps, not a circle or a square.
        double[] reach = new double[8];
        for (int i = 0; i < reach.length; i++) {
            double angle = i * Math.PI / 4;
            double r = 0;
            while (PhotoRenderer.boulderHeight(10, 20, 10.5 + Math.cos(angle) * r, 20.5 + Math.sin(angle) * r) > 0 && r < 2) {
                r += 0.005;
            }
            reach[i] = r;
        }
        double shortest = java.util.Arrays.stream(reach).min().orElseThrow();
        double longest = java.util.Arrays.stream(reach).max().orElseThrow();
        assertThat(longest - shortest).isGreaterThan(0.08);
        assertThat(shortest).isGreaterThan(0.2);
    }

    @Test
    void differentRocksHaveDifferentShapes() {
        assertThat(PhotoRenderer.boulderHeight(10, 20, 10.5, 20.5)).isNotEqualTo(PhotoRenderer.boulderHeight(11, 20, 11.5, 20.5));
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
