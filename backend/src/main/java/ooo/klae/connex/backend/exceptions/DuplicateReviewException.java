package ooo.klae.connex.backend.exceptions;

public class DuplicateReviewException extends ConflictException {
    private final String code;

    public DuplicateReviewException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
