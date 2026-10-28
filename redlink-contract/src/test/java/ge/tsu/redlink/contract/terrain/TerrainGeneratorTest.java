package ge.tsu.redlink.contract.terrain;

import org.junit.jupiter.api.Test;

import static ge.tsu.redlink.contract.terrain.TerrainGenerator.JEZERO_SIM;
import static ge.tsu.redlink.contract.terrain.TerrainGenerator.JEZERO_SIM_SEED;
import static org.assertj.core.api.Assertions.assertThat;

class TerrainGeneratorTest {

    private final Terrain terrain = TerrainGenerator.generate(JEZERO_SIM, JEZERO_SIM_SEED);

    @Test
    void sameSeedGivesIdenticalTerrain() {
        assertThat(TerrainGenerator.generate(JEZERO_SIM, JEZERO_SIM_SEED)).isEqualTo(terrain);
    }

    @Test
    void differentSeedGivesDifferentTerrain() {
        assertThat(TerrainGenerator.generate(JEZERO_SIM, JEZERO_SIM_SEED + 1)).isNotEqualTo(terrain);
    }

    @Test
    void usesTheFullHeightRange() {
        int min = Terrain.MAX_LEVEL;
        int max = 0;
        for (int y = 0; y < terrain.height(); y++) {
            for (int x = 0; x < terrain.width(); x++) {
                min = Math.min(min, terrain.levelAt(x, y));
                max = Math.max(max, terrain.levelAt(x, y));
            }
        }
        assertThat(min).isZero();
        assertThat(max).isEqualTo(Terrain.MAX_LEVEL);
    }

    @Test
    void landingZoneIsClearAndGentle() {
        int sx = JEZERO_SIM.startX();
        int sy = JEZERO_SIM.startY();
        for (int y = sy - 3; y <= sy + 3; y++) {
            for (int x = sx - 3; x <= sx + 3; x++) {
                if (Math.hypot(x - sx, y - sy) <= 3) {
                    assertThat(terrain.surfaceAt(x, y)).as("cell (%d,%d)", x, y).isEqualTo(SurfaceType.GROUND);
                    assertThat(terrain.slopeDegrees(sx, sy, x, y)).as("slope to (%d,%d)", x, y).isLessThan(5);
                }
            }
        }
    }

    @Test
    void hasSomeRocksAndSandButMostlyDrivableGround() {
        int cells = terrain.width() * terrain.height();
        long rocks = count(SurfaceType.ROCK);
        long sand = count(SurfaceType.SAND);

        assertThat(rocks).isBetween((long) (cells * 0.01), (long) (cells * 0.10));
        assertThat(sand).isPositive();
        assertThat(count(SurfaceType.GROUND)).isGreaterThan(cells / 2);
    }

    private long count(SurfaceType type) {
        long n = 0;
        for (int y = 0; y < terrain.height(); y++) {
            for (int x = 0; x < terrain.width(); x++) {
                if (terrain.surfaceAt(x, y) == type) {
                    n++;
                }
            }
        }
        return n;
    }
}
