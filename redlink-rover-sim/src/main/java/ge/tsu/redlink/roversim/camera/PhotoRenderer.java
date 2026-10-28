package ge.tsu.redlink.roversim.camera;

import ge.tsu.redlink.contract.rover.Camera;
import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.terrain.Terrain;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * What the rover's camera sees, drawn with Java2D from the terrain: a first-person "voxel space"
 * view (one ray per image column, marched outwards and drawn front to back), with a Mars sky,
 * surface colours, sun shading, distance haze and the crater rim beyond the map edge.
 *
 * <p>Pure and deterministic: the same terrain, position, heading and camera always give the same
 * PNG bytes. No text is drawn, so it works without any fonts installed.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PhotoRenderer {

    public static final int WIDTH = 320;
    public static final int HEIGHT = 240;

    private static final double MAST_HEIGHT_METERS = 1.8;
    private static final double BOULDER_HEIGHT_METERS = 0.7;
    private static final double RIM_HEIGHT_METERS = 5;
    private static final double MAX_DISTANCE_CELLS = 160;
    private static final double HORIZON = HEIGHT * 0.42;

    private static final Color SKY_TOP = new Color(158, 112, 82);
    private static final Color SKY_HORIZON = new Color(224, 184, 146);
    private static final int[] GROUND = {170, 104, 66};
    private static final int[] SAND = {206, 152, 98};
    private static final int[] ROCK = {84, 66, 58};
    private static final int[] RIM = {140, 82, 56};
    private static final int[] HAZE = {214, 170, 132};

    /**
     * @param x       grid column the rover stands on
     * @param y       grid row the rover stands on
     * @param heading the way the camera looks
     * @return the photo as PNG bytes, {@value #WIDTH}×{@value #HEIGHT}; NAVCAM is grayscale
     */
    public static byte[] render(Terrain terrain, int x, int y, Heading heading, Camera camera) {
        BufferedImage colour = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D sky = colour.createGraphics();
        sky.setPaint(new GradientPaint(0, 0, SKY_TOP, 0, (float) HORIZON, SKY_HORIZON));
        sky.fillRect(0, 0, WIDTH, HEIGHT);
        sky.dispose();

        new View(terrain, x, y, heading, camera).drawGround(colour);

        BufferedImage photo = colour;
        if (camera == Camera.NAVCAM) {
            photo = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D gray = photo.createGraphics();
            gray.drawImage(colour, 0, 0, null);
            gray.dispose();
        }
        drawReticle(photo);
        return png(photo);
    }

    /** Small corner marks, like the framing marks on real navigation images. */
    private static void drawReticle(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(255, 255, 255, 140));
        int m = 8;
        int len = 14;
        g.drawLine(m, m, m + len, m);
        g.drawLine(m, m, m, m + len);
        g.drawLine(WIDTH - 1 - m, m, WIDTH - 1 - m - len, m);
        g.drawLine(WIDTH - 1 - m, m, WIDTH - 1 - m, m + len);
        g.drawLine(m, HEIGHT - 1 - m, m + len, HEIGHT - 1 - m);
        g.drawLine(m, HEIGHT - 1 - m, m, HEIGHT - 1 - m - len);
        g.drawLine(WIDTH - 1 - m, HEIGHT - 1 - m, WIDTH - 1 - m - len, HEIGHT - 1 - m);
        g.drawLine(WIDTH - 1 - m, HEIGHT - 1 - m, WIDTH - 1 - m, HEIGHT - 1 - m - len);
        g.dispose();
    }

    private static byte[] png(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, "png", out)) {
                throw new IllegalStateException("No PNG writer available");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e); // cannot happen with an in-memory stream
        }
        return out.toByteArray();
    }

    /** One camera pose; all lengths are in cells, heights in meters. */
    private static final class View {

        private final Terrain terrain;
        private final double cameraX;
        private final double cameraY;
        private final double cameraHeight;
        private final double directionX;
        private final double directionY;
        private final double focalPixels;
        private final double metersPerCell;

        View(Terrain terrain, int x, int y, Heading heading, Camera camera) {
            this.terrain = terrain;
            this.cameraX = x + 0.5;
            this.cameraY = y + 0.5;
            this.cameraHeight = terrain.elevationAt(x, y) + MAST_HEIGHT_METERS;
            double angle = Math.toRadians(heading.ordinal() * 90); // north = 0, clockwise; row 0 is north
            this.directionX = Math.sin(angle);
            this.directionY = -Math.cos(angle);
            double fieldOfView = Math.toRadians(camera == Camera.NAVCAM ? 90 : 40);
            this.focalPixels = (WIDTH / 2.0) / Math.tan(fieldOfView / 2);
            this.metersPerCell = terrain.site().metersPerCell();
        }

        void drawGround(BufferedImage image) {
            for (int column = 0; column < WIDTH; column++) {
                double offset = (column + 0.5 - WIDTH / 2.0) / focalPixels;
                // Ray through this column: forward + offset * right; marching by forward distance
                // keeps the projection free of fisheye distortion.
                double rayX = directionX - offset * directionY;
                double rayY = directionY + offset * directionX;
                int lowestDrawn = HEIGHT;
                for (double depth = 0.3; depth < MAX_DISTANCE_CELLS && lowestDrawn > 0; depth += 0.02 + depth * 0.008) {
                    double sampleX = cameraX + rayX * depth;
                    double sampleY = cameraY + rayY * depth;
                    double height = heightAt(sampleX, sampleY);
                    int top = (int) Math.round(HORIZON + (cameraHeight - height) / (depth * metersPerCell) * focalPixels);
                    if (top < lowestDrawn) {
                        int rgb = shade(sampleX, sampleY, depth);
                        for (int row = Math.max(top, 0); row < lowestDrawn; row++) {
                            image.setRGB(column, row, rgb);
                        }
                        lowestDrawn = Math.max(top, 0);
                    }
                }
            }
        }

        /** Smooth ground (bilinear), boulders on rock cells, and a rising crater wall outside the map. */
        private double heightAt(double sx, double sy) {
            int cellX = (int) Math.floor(sx);
            int cellY = (int) Math.floor(sy);
            if (!terrain.contains(cellX, cellY)) {
                return terrain.site().maxElevationMeters() + 1 + Math.min(0.12 * outside(sx, sy) * metersPerCell, RIM_HEIGHT_METERS);
            }
            double fx = sx - 0.5 - Math.floor(sx - 0.5);
            int x0 = (int) Math.floor(sx - 0.5);
            double fy = sy - 0.5 - Math.floor(sy - 0.5);
            int y0 = (int) Math.floor(sy - 0.5);
            double top = lerp(elevation(x0, y0), elevation(x0 + 1, y0), fx);
            double bottom = lerp(elevation(x0, y0 + 1), elevation(x0 + 1, y0 + 1), fx);
            double ground = lerp(top, bottom, fy);
            return terrain.isBlocked(cellX, cellY) ? ground + BOULDER_HEIGHT_METERS : ground;
        }

        /** Elevation with the edge cells repeated, for interpolation at the map border. */
        private double elevation(int x, int y) {
            int cx = clamp(x, 0, terrain.width() - 1);
            int cy = clamp(y, 0, terrain.height() - 1);
            return terrain.elevationAt(cx, cy);
        }

        private double outside(double sx, double sy) {
            double dx = Math.max(Math.max(-sx, sx - terrain.width()), 0);
            double dy = Math.max(Math.max(-sy, sy - terrain.height()), 0);
            return Math.hypot(dx, dy);
        }

        private int shade(double sx, double sy, double depth) {
            int cellX = (int) Math.floor(sx);
            int cellY = (int) Math.floor(sy);
            int[] base;
            if (!terrain.contains(cellX, cellY)) {
                base = RIM;
            } else {
                base = switch (terrain.surfaceAt(cellX, cellY)) {
                    case GROUND -> GROUND;
                    case SAND -> SAND;
                    case ROCK -> ROCK;
                };
            }
            // Low sun in the west: slopes facing west are lit, slopes facing east fall into shade.
            double slope = heightAt(sx - 0.5, sy) - heightAt(sx + 0.5, sy);
            double light = clamp(0.82 + slope * 0.9, 0.45, 1.15);
            // Fine grain so flat ground is not a flat colour.
            double grain = 0.94 + 0.06 * noise(cellX, cellY);
            double haze = 1 - Math.exp(-depth * metersPerCell / 70);
            int r = channel(base[0] * light * grain, HAZE[0], haze);
            int g = channel(base[1] * light * grain, HAZE[1], haze);
            int b = channel(base[2] * light * grain, HAZE[2], haze);
            return (r << 16) | (g << 8) | b;
        }

        private static int channel(double value, int haze, double amount) {
            return (int) Math.round(clamp(value * (1 - amount) + haze * amount, 0, 255));
        }

        private static double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        private static double lerp(double a, double b, double t) {
            return a + (b - a) * t;
        }

        /** Deterministic per-cell value in [0, 1). */
        private static double noise(int x, int y) {
            int h = x * 374761393 + y * 668265263;
            h = (h ^ (h >>> 13)) * 1274126177;
            return ((h ^ (h >>> 16)) & 0xFFFF) / 65536.0;
        }
    }
}
