package ooo.klae.connex.backend.connectedaccounts;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Extracts stable, non-authorizing provider account identity from an exchanged id token.
 * The token comes directly from a fixed provider endpoint over TLS in the same exchange, so its
 * payload may supply display and connection-binding metadata without becoming an authentication
 * or account-linking assertion.
 */
@Component
@RequiredArgsConstructor
public class ProviderAccountIdentityResolver {

    private final ConnectedAccountProviders providers;
    private final ObjectMapper objectMapper;

    /** Resolves issuer, subject, and display email after checking the effective client audience. */
    public ProviderAccountIdentity resolve(String provider, String idToken) {
        if (idToken == null || idToken.isBlank()) {
            throw new ProviderTokenException(
                "identity_missing", "Provider token response omitted the account identity");
        }
        try {
            String[] parts = idToken.split("\\.", -1);
            if (parts.length < 2) {
                throw new ProviderTokenException(
                    "identity_malformed", "Provider account identity is malformed");
            }
            JsonNode claims = objectMapper.readTree(
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            String audience = textClaim(claims, "aud");
            if (!providers.effectiveClientId(provider).equals(audience)) {
                throw new ProviderTokenException(
                    "identity_audience_mismatch",
                    "Provider account identity was issued for a different client");
            }
            String subject = textClaim(claims, "sub");
            String issuer = textClaim(claims, "iss");
            if (subject == null || issuer == null) {
                throw new ProviderTokenException(
                    "identity_missing", "Provider account identity is incomplete");
            }
            String email = null;
            for (String claim : List.of("email", "preferred_username", "upn")) {
                if (claims.hasNonNull(claim)
                        && claims.get(claim).isString()
                        && !claims.get(claim).asString().isBlank()) {
                    email = claims.get(claim).asString();
                    break;
                }
            }
            return new ProviderAccountIdentity(
                provider + ":" + issuer + ":" + subject, email);
        } catch (ProviderTokenException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ProviderTokenException(
                "identity_malformed", "Provider account identity is malformed", exception);
        }
    }

    private static String textClaim(JsonNode claims, String field) {
        return claims.hasNonNull(field)
                && claims.get(field).isString()
                && !claims.get(field).asString().isBlank()
            ? claims.get(field).asString()
            : null;
    }

    /** Stable provider account identifier and nullable display email. */
    public record ProviderAccountIdentity(String accountId, String email) {
    }
}
