package ge.tsu.redlink.roversim.camera;

import ge.tsu.redlink.contract.rover.Camera;
import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.terrain.SurfaceType;
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
 * surface colours blended between cells, rounded boulders lit by the sun with their own shadows,
 * fine grain, distance haze and the crater rim beyond the map edge.
 *
 * <p>Pure and deterministic: the same terrain, position, heading and camera always give the same
 * PNG bytes. No text is drawn, so it works without any fonts installed.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PhotoRenderer {

    public static final int WIDTH = 320;
    public static final int HEIGHT = 240;

    private static final double MAST_HEIGHT_METERS = 1.8;
    /** Boulder radius in cells, before the lumps: 0.45 to 0.68, like the 3D view's. */
    private static final double BOULDER_MIN_RADIUS = 0.45;
    private static final double BOULDER_RADIUS_SPREAD = 0.23;
    /** Boulder height in meters: 0.3 to 0.7, lower than they are wide, like most rocks on Mars. */
    private static final double BOULDER_MIN_HEIGHT = 0.3;
    private static final double BOULDER_HEIGHT_SPREAD = 0.4;
    /** Towards the sun (x east, y up, z south in grid rows): low in the west, a little from the south. */
    private static final double[] SUN = normalized(-0.62, 0.55, 0.3);
    /** Sky light everywhere, plus direct sun by how squarely a surface faces it. */
    private static final double AMBIENT = 0.38;
    private static final double DIRECT = 0.8;
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
                for (double depth = 0.3; depth < MAX_DISTANCE_CELLS && lowestDrawn > 0; depth += 0.006 + depth * 0.008) {
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

        /** The surface: smooth ground (bilinear), rounded boulders, and a rising crater wall outside the map. */
        private double heightAt(double sx, double sy) {
            return groundAt(sx, sy) + boulderAt(sx, sy);
        }

        private double groundAt(double sx, double sy) {
            if (!terrain.contains((int) Math.floor(sx), (int) Math.floor(sy))) {
                // Beyond the map the ground rolls on from the edge and slowly rises towards the crater rim.
                double out = outside(sx, sy) * metersPerCell;
                double edge = bilinear(clamp(sx, 0.5, terrain.width() - 0.5), clamp(sy, 0.5, terrain.height() - 0.5));
                double rim = RIM_HEIGHT_METERS * (1 - Math.exp(-out / 45));
                double hills = 1.6 * smoothNoise(sx / 14, sy / 14) * Math.min(1, out / 12);
                return edge + rim + hills;
            }
            return bilinear(sx, sy);
        }

        /** Ground height between the four nearest cell centres. */
        private double bilinear(double sx, double sy) {
            double fx = sx - 0.5 - Math.floor(sx - 0.5);
            int x0 = (int) Math.floor(sx - 0.5);
            double fy = sy - 0.5 - Math.floor(sy - 0.5);
            int y0 = (int) Math.floor(sy - 0.5);
            double top = lerp(elevation(x0, y0), elevation(x0 + 1, y0), fx);
            double bottom = lerp(elevation(x0, y0 + 1), elevation(x0 + 1, y0 + 1), fx);
            return lerp(top, bottom, fy);
        }

        /** Height of the boulder (if any) over this point; a boulder may reach a little past its own cell. */
        private double boulderAt(double sx, double sy) {
            int cellX = (int) Math.floor(sx);
            int cellY = (int) Math.floor(sy);
            double highest = 0;
            for (int y = cellY - 1; y <= cellY + 1; y++) {
                for (int x = cellX - 1; x <= cellX + 1; x++) {
                    if (terrain.contains(x, y) && terrain.isBlocked(x, y)) {
                        highest = Math.max(highest, boulderHeight(x, y, sx, sy));
                    }
                }
            }
            return highest;
        }

        private boolean rockNear(double sx, double sy, int reach) {
            int cellX = (int) Math.floor(sx);
            int cellY = (int) Math.floor(sy);
            for (int y = cellY - reach; y <= cellY + reach; y++) {
                for (int x = cellX - reach; x <= cellX + reach; x++) {
                    if (terrain.contains(x, y) && terrain.isBlocked(x, y)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Whether a boulder stands between this point and the sun. */
        private boolean inShadow(double sx, double sy, double height) {
            double horizontal = Math.hypot(SUN[0], SUN[2]);
            double rise = SUN[1] / horizontal * metersPerCell; // meters up per cell towards the sun
            for (double t = 0.08; t <= 1.6; t += 0.12) {
                double qx = sx + SUN[0] / horizontal * t;
                double qy = sy + SUN[2] / horizontal * t;
                double boulder = boulderAt(qx, qy);
                if (boulder > 0 && groundAt(qx, qy) + boulder > height + rise * t) {
                    return true;
                }
            }
            return false;
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
            double boulder = boulderAt(sx, sy);
            double[] base = boulder > 0.02 ? rockColour(sx, sy, boulder) : groundColour(sx, sy);

            // Light from the surface's own normal, so boulders get a lit side and a shadow side.
            double e = 0.06;
            double gradientX = (heightAt(sx + e, sy) - heightAt(sx - e, sy)) / (2 * e * metersPerCell);
            double gradientY = (heightAt(sx, sy + e) - heightAt(sx, sy - e)) / (2 * e * metersPerCell);
            double length = Math.sqrt(gradientX * gradientX + 1 + gradientY * gradientY);
            double sunward = (-gradientX * SUN[0] + SUN[1] - gradientY * SUN[2]) / length;
            if (sunward > 0 && rockNear(sx, sy, 2) && inShadow(sx, sy, groundAt(sx, sy) + boulder)) {
                sunward = 0; // a boulder's shadow
            }
            double light = AMBIENT + DIRECT * Math.max(0, sunward);

            double haze = 1 - Math.exp(-depth * metersPerCell / 70);
            int r = channel(base[0] * light, HAZE[0], haze);
            int g = channel(base[1] * light, HAZE[1], haze);
            int b = channel(base[2] * light, HAZE[2], haze);
            return (r << 16) | (g << 8) | b;
        }

        /** Ground and sand blended between cell centres (no hard squares), with fine smooth grain. */
        private double[] groundColour(double sx, double sy) {
            int cellX = (int) Math.floor(sx);
            int cellY = (int) Math.floor(sy);
            if (!terrain.contains(cellX, cellY)) {
                return scaled(RIM, 0.9 + 0.15 * smoothNoise(sx * 0.7, sy * 0.7));
            }
            double fx = sx - 0.5 - Math.floor(sx - 0.5);
            int x0 = (int) Math.floor(sx - 0.5);
            double fy = sy - 0.5 - Math.floor(sy - 0.5);
            int y0 = (int) Math.floor(sy - 0.5);
            double[] colour = new double[3];
            for (int c = 0; c < 3; c++) {
                double top = lerp(surface(x0, y0)[c], surface(x0 + 1, y0)[c], fx);
                double bottom = lerp(surface(x0, y0 + 1)[c], surface(x0 + 1, y0 + 1)[c], fx);
                colour[c] = lerp(top, bottom, fy);
            }
            double grain = 0.9 + 0.1 * smoothNoise(sx * 2.5, sy * 2.5) + 0.06 * smoothNoise(sx * 9, sy * 9);
            return scaled(colour, grain);
        }

        /** Dark basalt, mottled, a little lighter on top where dust settles. */
        private double[] rockColour(double sx, double sy, double boulder) {
            double mottle = 0.8 + 0.28 * smoothNoise(sx * 5, sy * 5) + 0.1 * smoothNoise(sx * 13 + 50, sy * 13);
            double dusty = Math.min(1, boulder / 0.7) * 0.25;
            double[] colour = new double[3];
            for (int c = 0; c < 3; c++) {
                colour[c] = (ROCK[c] * (1 - dusty) + GROUND[c] * dusty) * mottle;
            }
            return colour;
        }

        /** The colour of the ground in a cell; under a boulder it is plain ground. */
        private int[] surface(int x, int y) {
            int cx = clamp(x, 0, terrain.width() - 1);
            int cy = clamp(y, 0, terrain.height() - 1);
            return terrain.surfaceAt(cx, cy) == SurfaceType.SAND ? SAND : GROUND;
        }

        private static double[] scaled(int[] colour, double factor) {
            return new double[]{colour[0] * factor, colour[1] * factor, colour[2] * factor};
        }

        private static double[] scaled(double[] colour, double factor) {
            return new double[]{colour[0] * factor, colour[1] * factor, colour[2] * factor};
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

    }

    /**
     * The boulder on rock cell (cellX, cellY), in meters above the ground at point (sx, sy) (cell
     * units, a cell's centre at +0.5): a rounded dome with a lumpy outline and a flattish top, 0
     * outside it. Size, offset and lumps come from hashes of the cell, the size from the same hash as
     * the 3D view's boulders, so a rock is the same rock in both.
     */
    static double boulderHeight(int cellX, int cellY, double sx, double sy) {
        double size = noise(cellX, cellY);
        double shape = noise(cellY + 31, cellX + 7);
        double centreX = cellX + 0.5 + (shape - 0.5) * 0.2;
        double centreY = cellY + 0.5 + (noise(cellX + 13, cellY + 5) - 0.5) * 0.2;
        double dx = sx - centreX;
        double dy = sy - centreY;
        double angle = Math.atan2(dy, dx);
        double radius = (BOULDER_MIN_RADIUS + size * BOULDER_RADIUS_SPREAD)
                * (1 + 0.14 * Math.sin(2 * angle + size * 7) + 0.18 * Math.sin(3 * angle + shape * 2 * Math.PI)
                + 0.08 * Math.sin(5 * angle + size * 2 * Math.PI));
        double d = Math.hypot(dx, dy) / radius;
        if (d >= 1) {
            return 0;
        }
        double height = BOULDER_MIN_HEIGHT + shape * BOULDER_HEIGHT_SPREAD;
        // Rounded sides and a flattish top, roughened by knobs and dents so no two rocks are smooth eggs.
        double knobs = 0.78 + 0.32 * smoothNoise(sx * 4.5 + cellX * 3.1, sy * 4.5 + cellY * 1.7)
                + 0.12 * smoothNoise(sx * 11, sy * 11);
        return height * Math.pow(1 - Math.pow(d, 2.2), 0.45) * knobs;
    }

    /** Deterministic per-cell value in [0, 1); the same hash as the 3D view's {@code hash2}. */
    static double noise(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65536.0;
    }

    /** Smooth value noise in [0, 1): {@link #noise} on a lattice, eased between the points. */
    static double smoothNoise(double u, double v) {
        int x0 = (int) Math.floor(u);
        int y0 = (int) Math.floor(v);
        double fx = ease(u - x0);
        double fy = ease(v - y0);
        double top = noise(x0, y0) + (noise(x0 + 1, y0) - noise(x0, y0)) * fx;
        double bottom = noise(x0, y0 + 1) + (noise(x0 + 1, y0 + 1) - noise(x0, y0 + 1)) * fx;
        return top + (bottom - top) * fy;
    }

    private static double ease(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double[] normalized(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / length, y / length, z / length};
    }
}
