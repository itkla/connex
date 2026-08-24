package ooo.klae.connex.backend.connectedaccounts;

import ooo.klae.connex.backend.beans.ProviderConnection;

/** Immutable connection presence and generation bound to one authorization attempt. */
public record ProviderConnectionExpectation(
    boolean present,
    int connectionId,
    long credentialGeneration
) {
    public ProviderConnectionExpectation {
        if (present && (connectionId <= 0 || credentialGeneration <= 0)) {
            throw new IllegalArgumentException("Present connection expectation is invalid");
        }
        if (!present && (connectionId != 0 || credentialGeneration != 0)) {
            throw new IllegalArgumentException("Absent connection expectation is invalid");
        }
    }

    /** Captures the current row identity and generation, or explicit absence. */
    public static ProviderConnectionExpectation snapshot(ProviderConnection connection) {
        return connection == null
            ? absent()
            : new ProviderConnectionExpectation(
                true, connection.getId(), connection.getCredentialGeneration());
    }

    /** Rehydrates the nullable native-session representation. */
    public static ProviderConnectionExpectation persisted(
            Integer connectionId, Long credentialGeneration) {
        if (connectionId == null && credentialGeneration == null) {
            return absent();
        }
        if (connectionId == null || credentialGeneration == null) {
            throw new IllegalArgumentException("Persisted connection expectation is incomplete");
        }
        return new ProviderConnectionExpectation(true, connectionId, credentialGeneration);
    }

    /** Returns an explicit absent-row expectation. */
    public static ProviderConnectionExpectation absent() {
        return new ProviderConnectionExpectation(false, 0, 0);
    }

    /** Checks a locked current row against this authorization boundary. */
    public boolean matches(ProviderConnection connection) {
        return present
            ? connection != null
                && connection.getId() == connectionId
                && connection.getCredentialGeneration() == credentialGeneration
            : connection == null;
    }
}
