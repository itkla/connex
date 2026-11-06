package ooo.klae.connex.backend.exceptions;

import java.util.Map;

/** A correctable import refusal with a stable code for localized recovery guidance. */
public class ImportValidationException extends BadRequestException {
    private final String code;
    private final Map<String, String> fieldErrors;

    public ImportValidationException(String code, String diagnostic) {
        this(code, diagnostic, Map.of());
    }

    public ImportValidationException(String code, String diagnostic, Map<String, String> fieldErrors) {
        super(diagnostic);
        this.code = code;
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    @Override
    public String getCode() {
        return code;
    }

    /** Stable problem codes keyed by the affected source columns, without imported values. */
    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
