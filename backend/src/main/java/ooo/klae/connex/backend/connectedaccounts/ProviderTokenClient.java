package ooo.klae.connex.backend.connectedaccounts;

import java.util.Map;

/**
 * Exchanges and revokes OAuth tokens against a provider's fixed endpoints. An interface so
 * tests can substitute a stub without real network calls.
 */
public interface ProviderTokenClient {

    /**
     * Exchanges an authorization code for tokens at the provider's token endpoint.
     *
     * @param tokenUri the provider token endpoint (fixed host from the provider catalog)
     * @param form the form parameters (code, client credentials, redirect_uri, grant_type)
     * @return the fields Connex consumes from the response
     * @throws ProviderTokenException when the provider rejects the exchange or the response is unusable
     */
    ProviderTokenResponse exchange(String tokenUri, Map<String, String> form);

    /**
     * Best-effort token revocation at the provider; failures are swallowed by callers because
     * local deletion must never be blocked by provider availability.
     *
     * @param revokeUri the provider revocation endpoint
     * @param token the token to revoke
     */
    void revoke(String revokeUri, String token);
}
