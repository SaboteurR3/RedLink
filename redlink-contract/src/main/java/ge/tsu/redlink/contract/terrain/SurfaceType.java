package ge.tsu.redlink.contract.terrain;

import lombok.RequiredArgsConstructor;

/**
 * What a cell is made of. Stored in {@code surface.png} as the gray value in {@link #pixel()}.
 */
@RequiredArgsConstructor
public enum SurfaceType {
    GROUND(0, false),
    /** Loose sand: drivable, but costs more battery. */
    SAND(128, false),
    /** A rock the rover cannot drive over. */
    ROCK(255, true);

    private final int pixel;
    private final boolean blocked;

    public int pixel() {
        return pixel;
    }

    public boolean blocked() {
        return blocked;
    }

    /**
     * @throws IllegalArgumentException if the gray value is not one of the defined surfaces
     */
    public static SurfaceType fromPixel(int pixel) {
        for (SurfaceType type : values()) {
            if (type.pixel == pixel) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown surface value " + pixel);
    }
}
