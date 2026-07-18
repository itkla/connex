package ooo.klae.connex.backend.connectedaccounts;

/**
 * The fields Connex consumes from a provider token response. Tokens never leave the
 * connected-accounts service unencrypted; this record is never serialized to clients or logs.
 *
 * @param accessToken  short-lived access token
 * @param refreshToken long-lived refresh token; may be null when the provider withholds it
 * @param expiresIn    access-token lifetime in seconds; may be null
 * @param scope        granted scopes as reported by the provider; may be null
 * @param idToken      OpenID id token carrying the account identity; may be null
 */
public record ProviderTokenResponse(
    String accessToken,
    String refreshToken,
    Long expiresIn,
    String scope,
    String idToken
) {}
