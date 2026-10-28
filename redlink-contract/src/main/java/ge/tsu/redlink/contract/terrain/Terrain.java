package ge.tsu.redlink.contract.terrain;

import java.util.Arrays;
import java.util.Objects;

/**
 * An immutable grid of cells, row-major with row 0 at the north edge. Elevation is stored as
 * heightmap levels 0–{@value #MAX_LEVEL} so a write/load round-trip through PNG is exact.
 */
public final class Terrain {

    public static final int MAX_LEVEL = 255;

    private final SiteInfo site;
    private final int[] levels;
    private final SurfaceType[] surface;

    /**
     * @param levels  heightmap levels, {@code width * height} values in 0–255
     * @param surface surface types, {@code width * height} values; the start cell must not be blocked
     */
    public Terrain(SiteInfo site, int[] levels, SurfaceType[] surface) {
        this.site = Objects.requireNonNull(site, "site");
        int cells = site.width() * site.height();
        if (levels.length != cells || surface.length != cells) {
            throw new IllegalArgumentException("Expected " + cells + " cells");
        }
        for (int level : levels) {
            if (level < 0 || level > MAX_LEVEL) {
                throw new IllegalArgumentException("Heightmap level out of range: " + level);
            }
        }
        if (Arrays.asList(surface).contains(null)) {
            throw new IllegalArgumentException("Surface contains null cells");
        }
        this.levels = levels.clone();
        this.surface = surface.clone();
        if (isBlocked(site.startX(), site.startY())) {
            throw new IllegalArgumentException("Start cell is blocked");
        }
    }

    public SiteInfo site() {
        return site;
    }

    public int width() {
        return site.width();
    }

    public int height() {
        return site.height();
    }

    public boolean contains(int x, int y) {
        return x >= 0 && x < site.width() && y >= 0 && y < site.height();
    }

    public int levelAt(int x, int y) {
        return levels[index(x, y)];
    }

    public double elevationAt(int x, int y) {
        double range = site.maxElevationMeters() - site.minElevationMeters();
        return site.minElevationMeters() + levelAt(x, y) * range / MAX_LEVEL;
    }

    public SurfaceType surfaceAt(int x, int y) {
        return surface[index(x, y)];
    }

    /**
     * @return true for rocks and for every cell outside the map (the map edge is a wall)
     */
    public boolean isBlocked(int x, int y) {
        return !contains(x, y) || surfaceAt(x, y).blocked();
    }

    /**
     * Steepness of the straight line between two cell centers, 0–90 degrees.
     */
    public double slopeDegrees(int fromX, int fromY, int toX, int toY) {
        double rise = Math.abs(elevationAt(toX, toY) - elevationAt(fromX, fromY));
        double run = Math.hypot(toX - fromX, toY - fromY) * site.metersPerCell();
        return run == 0 ? 0 : Math.toDegrees(Math.atan(rise / run));
    }

    private int index(int x, int y) {
        if (!contains(x, y)) {
            throw new IndexOutOfBoundsException("Cell (" + x + "," + y + ") is outside the "
                    + site.width() + "x" + site.height() + " grid");
        }
        return y * site.width() + x;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Terrain other
                && site.equals(other.site)
                && Arrays.equals(levels, other.levels)
                && Arrays.equals(surface, other.surface);
    }

    @Override
    public int hashCode() {
        return Objects.hash(site, Arrays.hashCode(levels), Arrays.hashCode(surface));
    }

    @Override
    public String toString() {
        return "Terrain[" + site.id() + ", " + site.width() + "x" + site.height() + "]";
    }
}
