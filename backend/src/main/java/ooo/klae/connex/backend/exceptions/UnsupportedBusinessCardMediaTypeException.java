package ooo.klae.connex.backend.exceptions;

/**
 * Signals that an uploaded business-card file is not a supported raster image.
 */
public class UnsupportedBusinessCardMediaTypeException extends RuntimeException {
    public UnsupportedBusinessCardMediaTypeException(String message) {
        super(message);
    }
}
