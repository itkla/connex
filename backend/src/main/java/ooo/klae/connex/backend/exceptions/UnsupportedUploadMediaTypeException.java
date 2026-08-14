package ooo.klae.connex.backend.exceptions;

/**
 * Raised when an uploaded file's declared or detected media type is not accepted.
 */
public class UnsupportedUploadMediaTypeException extends RuntimeException {
    private static final String SAFE_MESSAGE = "Upload a supported file type";

    public UnsupportedUploadMediaTypeException(String message) {
        super(message);
    }

    /** @return a rejection with a fixed detail-free message */
    public static UnsupportedUploadMediaTypeException unsupported() {
        return new UnsupportedUploadMediaTypeException(SAFE_MESSAGE);
    }
}
