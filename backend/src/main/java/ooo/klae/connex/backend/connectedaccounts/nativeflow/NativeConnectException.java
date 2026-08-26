package ooo.klae.connex.backend.connectedaccounts.nativeflow;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/** Sanitized native-connect rejection carrying a stable machine-readable error code. */
public class NativeConnectException extends BadRequestException {
    private final String code;

    public NativeConnectException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
