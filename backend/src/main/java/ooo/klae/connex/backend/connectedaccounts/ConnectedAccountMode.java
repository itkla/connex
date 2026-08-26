package ooo.klae.connex.backend.connectedaccounts;

/** Connected-account OAuth client ownership mode. */
public enum ConnectedAccountMode {
    /** Operator-supplied OAuth web client credentials. */
    CUSTOM,
    /** Connex-managed installed-application identity with loopback PKCE authorization. */
    MANAGED
}
