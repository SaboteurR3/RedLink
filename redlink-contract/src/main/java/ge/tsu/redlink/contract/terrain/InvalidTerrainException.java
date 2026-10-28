package ge.tsu.redlink.contract.terrain;

/**
 * A site folder is missing, unreadable or inconsistent.
 */
public class InvalidTerrainException extends RuntimeException {

    public InvalidTerrainException(String message) {
        super(message);
    }

    public InvalidTerrainException(String message, Throwable cause) {
        super(message, cause);
    }
}
