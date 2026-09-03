package ooo.klae.connex.backend.exceptions;

import org.springframework.http.HttpStatus;

/** Sanitized domain failure raised by sequence authoring and preview operations. */
public class SequenceException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    /** Creates a sanitized sequence-domain failure with its HTTP status and stable code. */
    public SequenceException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** Returns the response status associated with this failure. */
    public HttpStatus status() {
        return status;
    }

    /** Returns the stable client-facing error code. */
    public String code() {
        return code;
    }

    /** Creates a bad-request failure. */
    public static SequenceException badRequest(String code, String message) {
        return new SequenceException(HttpStatus.BAD_REQUEST, code, message);
    }

    /** Creates a not-found failure without revealing workspace existence. */
    public static SequenceException notFound(String message) {
        return new SequenceException(HttpStatus.NOT_FOUND, "SEQUENCE_NOT_FOUND", message);
    }
}
