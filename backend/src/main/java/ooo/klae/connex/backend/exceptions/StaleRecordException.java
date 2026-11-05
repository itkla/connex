package ooo.klae.connex.backend.exceptions;

/** A conditional record update lost to a newer committed version. */
public class StaleRecordException extends ConflictException {
    private final long currentVersion;

    public StaleRecordException(long currentVersion) {
        super("The record changed since this edit was opened");
        this.currentVersion = currentVersion;
    }

    public long getCurrentVersion() {
        return currentVersion;
    }
}
