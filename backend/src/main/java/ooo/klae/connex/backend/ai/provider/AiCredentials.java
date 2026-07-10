package ooo.klae.connex.backend.ai.provider;

import java.util.Objects;

/**
 * Decrypted AWS credential material for an organization-scoped BYOP provider.
 * @param accessKeyId AWS access key id
 * @param secretAccessKey AWS secret access key
 * @param sessionToken optional AWS session token for temporary credentials
 */
public record AiCredentials(String accessKeyId, String secretAccessKey, String sessionToken) {

    public AiCredentials {
        Objects.requireNonNull(accessKeyId, "accessKeyId");
        Objects.requireNonNull(secretAccessKey, "secretAccessKey");
    }

    @Override
    public String toString() {
        return "AiCredentials[accessKeyId=<redacted>, secretAccessKey=<redacted>, sessionToken=<redacted>]";
    }
}
