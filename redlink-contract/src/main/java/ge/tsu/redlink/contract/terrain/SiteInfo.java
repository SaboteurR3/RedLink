package ge.tsu.redlink.contract.terrain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Metadata of a landing site: the grid and how to turn heightmap pixels into meters.
 *
 * @param id                 lowercase slug; also the folder name and the id in URLs
 * @param width              grid columns (x), one cell per {@code metersPerCell}
 * @param height             grid rows (y); row 0 is the north edge
 * @param minElevationMeters elevation of heightmap value 0
 * @param maxElevationMeters elevation of heightmap value 255
 * @param startX             column where rovers land
 * @param startY             row where rovers land
 */
public record SiteInfo(
        String id,
        String name,
        int width,
        int height,
        double metersPerCell,
        double minElevationMeters,
        double maxElevationMeters,
        int startX,
        int startY) {

    /** Restricted so an id can never escape the sites folder (no {@code ..}, no slashes). */
    public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    public static final int MAX_CELLS_PER_SIDE = 1024;

    public SiteInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid site id: " + id);
        }
        if (width < 2 || height < 2 || width > MAX_CELLS_PER_SIDE || height > MAX_CELLS_PER_SIDE) {
            throw new IllegalArgumentException("Grid must be between 2 and " + MAX_CELLS_PER_SIDE + " cells per side");
        }
        if (!(metersPerCell > 0)) {
            throw new IllegalArgumentException("metersPerCell must be positive");
        }
        if (!(maxElevationMeters > minElevationMeters)) {
            throw new IllegalArgumentException("maxElevationMeters must be greater than minElevationMeters");
        }
        if (startX < 0 || startX >= width || startY < 0 || startY >= height) {
            throw new IllegalArgumentException("Start cell (" + startX + "," + startY + ") is outside the grid");
        }
    }
}
