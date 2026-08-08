package ooo.klae.connex.backend.exceptions;

/** Refuses tenant teardown while a data-subject obligation remains unresolved. */
public class OpenDataSubjectRequestException extends ConflictException {
    public static final String CODE = "TENANT_TEARDOWN_OPEN_DATA_SUBJECT_REQUEST";

    public OpenDataSubjectRequestException(String message) {
        super(message);
    }

    public String getCode() {
        return CODE;
    }
}
