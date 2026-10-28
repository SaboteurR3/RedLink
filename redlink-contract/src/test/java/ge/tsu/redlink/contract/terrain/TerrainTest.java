package ge.tsu.redlink.contract.terrain;

import org.junit.jupiter.api.Test;

import static ge.tsu.redlink.contract.terrain.SurfaceType.GROUND;
import static ge.tsu.redlink.contract.terrain.SurfaceType.ROCK;
import static ge.tsu.redlink.contract.terrain.SurfaceType.SAND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class TerrainTest {

    // 3x3 grid, 1 m cells, level 255 = 2.55 m, so one level is exactly 1 cm.
    private static final SiteInfo SITE = new SiteInfo("test", "Test", 3, 3, 1.0, 0.0, 2.55, 1, 1);

    private static final int[] LEVELS = {
            0, 100, 0,
            0, 0, 0,
            0, 0, 255};
    private static final SurfaceType[] SURFACE = {
            GROUND, ROCK, GROUND,
            SAND, GROUND, GROUND,
            GROUND, GROUND, GROUND};

    private final Terrain terrain = new Terrain(SITE, LEVELS, SURFACE);

    @Test
    void convertsLevelsToMeters() {
        assertThat(terrain.elevationAt(0, 0)).isEqualTo(0.0);
        assertThat(terrain.elevationAt(1, 0)).isCloseTo(1.0, within(1e-9));
        assertThat(terrain.elevationAt(2, 2)).isCloseTo(2.55, within(1e-9));
    }

    @Test
    void looksUpSurfaceByColumnAndRow() {
        assertThat(terrain.surfaceAt(1, 0)).isEqualTo(ROCK);
        assertThat(terrain.surfaceAt(0, 1)).isEqualTo(SAND);
    }

    @Test
    void rocksAndEverythingOutsideTheMapAreBlocked() {
        assertThat(terrain.isBlocked(1, 0)).isTrue();
        assertThat(terrain.isBlocked(0, 1)).isFalse();
        assertThat(terrain.isBlocked(-1, 0)).isTrue();
        assertThat(terrain.isBlocked(3, 0)).isTrue();
        assertThat(terrain.isBlocked(0, 3)).isTrue();
    }

    @Test
    void outOfBoundsLookupThrows() {
        assertThatThrownBy(() -> terrain.elevationAt(3, 0)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void slopeBetweenNeighbours() {
        // 1 m rise over 1 m run.
        assertThat(terrain.slopeDegrees(0, 0, 1, 0)).isCloseTo(45.0, within(1e-9));
        assertThat(terrain.slopeDegrees(1, 0, 0, 0)).isCloseTo(45.0, within(1e-9));
        assertThat(terrain.slopeDegrees(0, 1, 1, 1)).isEqualTo(0.0);
        assertThat(terrain.slopeDegrees(1, 1, 1, 1)).isEqualTo(0.0);
    }

    @Test
    void diagonalSlopeUsesDiagonalDistance() {
        // 2.55 m rise over sqrt(2) m.
        assertThat(terrain.slopeDegrees(1, 1, 2, 2))
                .isCloseTo(Math.toDegrees(Math.atan(2.55 / Math.sqrt(2))), within(1e-9));
    }

    @Test
    void isImmutable() {
        int[] levels = LEVELS.clone();
        Terrain copy = new Terrain(SITE, levels, SURFACE);
        levels[0] = 200;

        assertThat(copy.levelAt(0, 0)).isZero();
    }

    @Test
    void rejectsBlockedStartCell() {
        SurfaceType[] surface = SURFACE.clone();
        surface[4] = ROCK;

        assertThatThrownBy(() -> new Terrain(SITE, LEVELS, surface))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Start cell");
    }

    @Test
    void rejectsWrongCellCountAndLevelRange() {
        assertThatThrownBy(() -> new Terrain(SITE, new int[8], SURFACE)).isInstanceOf(IllegalArgumentException.class);

        int[] levels = LEVELS.clone();
        levels[0] = 256;
        assertThatThrownBy(() -> new Terrain(SITE, levels, SURFACE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void siteIdCannotEscapeTheSitesFolder() {
        assertThatThrownBy(() -> new SiteInfo("../etc", "x", 3, 3, 1, 0, 1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SiteInfo("a/b", "x", 3, 3, 1, 0, 1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
