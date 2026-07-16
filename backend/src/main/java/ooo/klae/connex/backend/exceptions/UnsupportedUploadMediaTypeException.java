package ooo.klae.connex.backend.exceptions;

/**
 * Raised when an uploaded file's declared or detected media type is not accepted.
 */
public class UnsupportedUploadMediaTypeException extends RuntimeException {
    public UnsupportedUploadMediaTypeException(String message) {
        super(message);
    }
}
