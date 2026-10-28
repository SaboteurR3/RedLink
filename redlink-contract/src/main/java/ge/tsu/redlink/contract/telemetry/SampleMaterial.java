package ge.tsu.redlink.contract.telemetry;

/** What a DRILL_SAMPLE drilled into. */
public enum SampleMaterial {
    /** The ground just in front of the rover's wheels. */
    SOIL,
    /** The rock in the cell the rover faces; the rock stays where it is, only a core is taken. */
    ROCK
}
