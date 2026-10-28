package ge.tsu.redlink.roversim.events;

/**
 * Every random decision of the simulation goes through here, so tests can replace it with a
 * scripted one and stay deterministic.
 */
@FunctionalInterface
public interface Dice {

    /** @return a value in [0, 1) */
    double roll();

    /** True with probability {@code chance} (0–1). */
    default boolean chance(double chance) {
        return chance > 0 && roll() < chance;
    }
}
