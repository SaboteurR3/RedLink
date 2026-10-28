package ge.tsu.redlink.roversim.flight;

import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.rover.RoverMode;
import ge.tsu.redlink.contract.telemetry.TelemetryPayload;

/**
 * Immutable rover state as the flight rules see it; persistence lives in {@code RoverState}.
 */
public record RoverSnapshot(
        int x,
        int y,
        Heading heading,
        double batteryPercent,
        double temperatureCelsius,
        int storageUsedPercent,
        RoverMode mode) {

    RoverSnapshot movedTo(int newX, int newY, double batteryCost) {
        return new RoverSnapshot(newX, newY, heading, drain(batteryCost), temperatureCelsius, storageUsedPercent, mode);
    }

    RoverSnapshot facing(Heading newHeading, double batteryCost) {
        return new RoverSnapshot(x, y, newHeading, drain(batteryCost), temperatureCelsius, storageUsedPercent, mode);
    }

    RoverSnapshot stored(int storagePercent, double batteryCost) {
        return new RoverSnapshot(x, y, heading, drain(batteryCost), temperatureCelsius,
                storageUsedPercent + storagePercent, mode);
    }

    RoverSnapshot charged(double percent) {
        return new RoverSnapshot(x, y, heading, round(Math.min(100, batteryPercent + percent)), temperatureCelsius,
                storageUsedPercent, mode);
    }

    public RoverSnapshot inMode(RoverMode newMode) {
        return new RoverSnapshot(x, y, heading, batteryPercent, temperatureCelsius, storageUsedPercent, newMode);
    }

    public TelemetryPayload.RoverState toTelemetry() {
        return new TelemetryPayload.RoverState(x, y, heading, batteryPercent, temperatureCelsius, storageUsedPercent, mode);
    }

    private double drain(double cost) {
        return round(Math.max(0, batteryPercent - cost));
    }

    /** Two decimals, so repeated small costs don't accumulate floating-point noise. */
    private static double round(double percent) {
        return Math.round(percent * 100) / 100.0;
    }
}
