package ge.tsu.redlink.contract.terrain;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Generates a Mars-like site from a seed: rolling ground, impact craters with raised rims, sand
 * patches and scattered rocks, with a flat, rock-free landing zone around the start cell.
 *
 * <p>Deterministic: {@link Random} has a specified algorithm, so the same seed gives the same
 * terrain on every JVM.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TerrainGenerator {

    /** The default site shipped in {@code sites/jezero-sim}. */
    public static final SiteInfo JEZERO_SIM = new SiteInfo(
            "jezero-sim", "Jezero Crater (simulated)", 64, 64, 1.0, 0.0, 4.0, 32, 32);
    /** 2021: the year Perseverance landed in Jezero crater. */
    public static final long JEZERO_SIM_SEED = 2021L;
    /** The second site (ticket 32), for the second rover. */
    public static final SiteInfo GALE_SIM = new SiteInfo(
            "gale-sim", "Gale Crater (simulated)", 64, 64, 1.0, 0.0, 5.0, 20, 40);
    /** 2012: the year Curiosity landed in Gale crater. */
    public static final long GALE_SIM_SEED = 2012L;
    /** Every shipped site and its seed. */
    public static final Map<SiteInfo, Long> SHIPPED = Map.of(JEZERO_SIM, JEZERO_SIM_SEED, GALE_SIM, GALE_SIM_SEED);

    private static final int LANDING_CLEAR_RADIUS = 3;
    private static final int LANDING_FLAT_RADIUS = 5;
    private static final double BASE_FREQUENCY = 3;
    private static final int OCTAVES = 4;
    private static final double SAND_THRESHOLD = 0.68;
    private static final double ROCK_PROBABILITY = 0.02;
    private static final double RIM_ROCK_PROBABILITY = 0.10;

    /**
     * Regenerates a shipped site. Usage: {@code TerrainGenerator <site id> <output folder>}, e.g.
     * {@code gale-sim redlink-contract/src/main/resources/sites/gale-sim}.
     */
    public static void main(String[] args) {
        SiteInfo site = args.length != 2 ? null
                : SHIPPED.keySet().stream().filter(s -> s.id().equals(args[0])).findFirst().orElse(null);
        if (site == null) {
            System.err.println("Usage: TerrainGenerator <jezero-sim|gale-sim> <output folder>");
            System.exit(2);
        }
        Path output = Path.of(args[1]);
        TerrainWriter.write(generate(site, SHIPPED.get(site)), output);
        System.out.println("Wrote " + site.id() + " to " + output.toAbsolutePath());
    }

    public static Terrain generate(SiteInfo site, long seed) {
        Random random = new Random(seed);
        int width = site.width();
        int height = site.height();

        double[] elevation = new double[width * height];
        ValueNoise ground = new ValueNoise(random, BASE_FREQUENCY, OCTAVES);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                elevation[y * width + x] = ground.sample((double) x / width, (double) y / height);
            }
        }

        List<Crater> craters = placeCraters(site, random);
        for (Crater crater : craters) {
            crater.carve(elevation, width, height);
        }
        flattenLandingZone(site, elevation);
        int[] levels = toLevels(elevation);

        ValueNoise sand = new ValueNoise(random, 2, 2);
        SurfaceType[] surface = new SurfaceType[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                surface[y * width + x] = pickSurface(site, craters, sand, seed, x, y);
            }
        }
        return new Terrain(site, levels, surface);
    }

    private static List<Crater> placeCraters(SiteInfo site, Random random) {
        int wanted = 3 + random.nextInt(3);
        List<Crater> craters = new ArrayList<>();
        for (int attempt = 0; attempt < 100 && craters.size() < wanted; attempt++) {
            Crater crater = new Crater(
                    random.nextDouble() * site.width(),
                    random.nextDouble() * site.height(),
                    4 + random.nextDouble() * 6,
                    0.35 + random.nextDouble() * 0.2,
                    0.12 + random.nextDouble() * 0.08);
            double toStart = Math.hypot(crater.x - site.startX(), crater.y - site.startY());
            if (toStart > crater.radius * 1.5 + LANDING_FLAT_RADIUS) {
                craters.add(crater);
            }
        }
        return craters;
    }

    /** Blends the area around the start cell toward its average height: a safe landing ellipse. */
    private static void flattenLandingZone(SiteInfo site, double[] elevation) {
        int width = site.width();
        double sum = 0;
        int count = 0;
        for (int y = 0; y < site.height(); y++) {
            for (int x = 0; x < width; x++) {
                if (distanceToStart(site, x, y) <= LANDING_FLAT_RADIUS) {
                    sum += elevation[y * width + x];
                    count++;
                }
            }
        }
        double average = sum / count;
        for (int y = 0; y < site.height(); y++) {
            for (int x = 0; x < width; x++) {
                double d = distanceToStart(site, x, y);
                double weight = d <= LANDING_CLEAR_RADIUS ? 1
                        : Math.max(0, (LANDING_FLAT_RADIUS - d) / (LANDING_FLAT_RADIUS - LANDING_CLEAR_RADIUS));
                int i = y * width + x;
                elevation[i] = elevation[i] * (1 - weight) + average * weight;
            }
        }
    }

    private static int[] toLevels(double[] elevation) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double e : elevation) {
            min = Math.min(min, e);
            max = Math.max(max, e);
        }
        int[] levels = new int[elevation.length];
        for (int i = 0; i < elevation.length; i++) {
            levels[i] = (int) Math.round((elevation[i] - min) / (max - min) * Terrain.MAX_LEVEL);
        }
        return levels;
    }

    private static SurfaceType pickSurface(SiteInfo site, List<Crater> craters, ValueNoise sand,
                                           long seed, int x, int y) {
        if (distanceToStart(site, x, y) <= LANDING_CLEAR_RADIUS) {
            return SurfaceType.GROUND;
        }
        boolean onRim = craters.stream().anyMatch(c -> c.isOnRim(x, y));
        if (cellRoll(seed, x, y) < (onRim ? RIM_ROCK_PROBABILITY : ROCK_PROBABILITY)) {
            return SurfaceType.ROCK;
        }
        return sand.sample((double) x / site.width(), (double) y / site.height()) > SAND_THRESHOLD
                ? SurfaceType.SAND : SurfaceType.GROUND;
    }

    /**
     * Independent random number in [0, 1) per cell, from a hash of (seed, x, y). Drawing these from
     * one {@link Random} sequence row by row lines rocks up in columns, because a linear congruential
     * generator's outputs are correlated at a fixed stride (here: one grid row).
     */
    private static double cellRoll(long seed, int x, int y) {
        long h = seed * 0x9E3779B97F4A7C15L + ((long) x << 32 | (y & 0xFFFFFFFFL));
        // SplitMix64 finalizer: spreads every input bit over the whole output.
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53;
    }

    private static double distanceToStart(SiteInfo site, int x, int y) {
        return Math.hypot(x - site.startX(), y - site.startY());
    }

    /**
     * A bowl below the surrounding ground with a raised rim, in normalized elevation units.
     */
    private record Crater(double x, double y, double radius, double depth, double rimHeight) {

        void carve(double[] elevation, int width, int height) {
            for (int cy = 0; cy < height; cy++) {
                for (int cx = 0; cx < width; cx++) {
                    double d = Math.hypot(cx - x, cy - y);
                    double change = 0;
                    if (d < radius) {
                        double t = d / radius;
                        change -= depth * (1 - t * t);
                    }
                    double rimDistance = (d - radius) / (0.25 * radius);
                    change += rimHeight * Math.exp(-rimDistance * rimDistance);
                    elevation[cy * width + cx] += change;
                }
            }
        }

        boolean isOnRim(int cx, int cy) {
            return Math.abs(Math.hypot(cx - x, cy - y) - radius) < 0.25 * radius;
        }
    }

    /**
     * Fractal value noise in [0, 1]: random values on a lattice, smoothly interpolated, summed over
     * octaves of doubling frequency and halving amplitude.
     */
    private static final class ValueNoise {

        private final double[][] lattices;
        private final int[] sizes;

        ValueNoise(Random random, double baseFrequency, int octaves) {
            lattices = new double[octaves][];
            sizes = new int[octaves];
            for (int octave = 0; octave < octaves; octave++) {
                int size = (int) Math.ceil(baseFrequency * (1 << octave)) + 2;
                sizes[octave] = size;
                lattices[octave] = new double[size * size];
                for (int i = 0; i < lattices[octave].length; i++) {
                    lattices[octave][i] = random.nextDouble();
                }
            }
        }

        /** @param u horizontal position 0..1, @param v vertical position 0..1 */
        double sample(double u, double v) {
            double total = 0;
            double amplitude = 1;
            double amplitudeSum = 0;
            for (int octave = 0; octave < lattices.length; octave++) {
                double scale = sizes[octave] - 2;
                total += amplitude * interpolate(octave, u * scale, v * scale);
                amplitudeSum += amplitude;
                amplitude /= 2;
            }
            return total / amplitudeSum;
        }

        private double interpolate(int octave, double px, double py) {
            int x0 = (int) Math.floor(px);
            int y0 = (int) Math.floor(py);
            double tx = smooth(px - x0);
            double ty = smooth(py - y0);
            double top = lerp(at(octave, x0, y0), at(octave, x0 + 1, y0), tx);
            double bottom = lerp(at(octave, x0, y0 + 1), at(octave, x0 + 1, y0 + 1), tx);
            return lerp(top, bottom, ty);
        }

        private double at(int octave, int x, int y) {
            return lattices[octave][y * sizes[octave] + x];
        }

        private static double smooth(double t) {
            return t * t * (3 - 2 * t);
        }

        private static double lerp(double a, double b, double t) {
            return a + (b - a) * t;
        }
    }
}
