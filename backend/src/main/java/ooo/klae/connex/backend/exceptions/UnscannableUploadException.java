package ooo.klae.connex.backend.exceptions;

/**
 * Rejects an upload whose complete contents could not be security-scanned.
 */
public class UnscannableUploadException extends RuntimeException {
    public static final String CODE = "UNSCANNABLE_UPLOAD";
    public static final String MESSAGE =
            "This file could not be security-scanned and was not accepted.";

    public UnscannableUploadException() {
        super(MESSAGE);
    }

    public String getCode() {
        return CODE;
    }
}
