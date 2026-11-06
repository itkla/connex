package ooo.klae.connex.backend.exceptions;

/** A correctable import refusal with a stable code for localized recovery guidance. */
public class ImportValidationException extends BadRequestException {
    private final String code;

    public ImportValidationException(String code, String diagnostic) {
        super(diagnostic);
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }
}
