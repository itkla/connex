package ooo.klae.connex.backend.observability;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.config.AuditIntegrityProperties;

/**
 * Produces domain-separated HMACs for client-asserted correlation identifiers.
 *
 * <p>The client value remains useful as a lookup input without persisting or disclosing the value
 * itself. Storage and disclosure use separate domains so a bundle value cannot be replayed as a
 * database lookup key. The HMAC limits disclosure and encoding, but does not authenticate the
 * provenance of the client assertion.
 */
@Component
@RequiredArgsConstructor
public class ClientAssertedCorrelationPseudonymizer {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final byte[] STORAGE_DOMAIN =
        "connex.client-correlation.storage.v2\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DISCLOSURE_DOMAIN =
        "connex.client-correlation.disclosure.v2\0".getBytes(StandardCharsets.UTF_8);

    private final AuditIntegrityProperties properties;

    /** Returns the organization-scoped persistence and lookup HMAC for a validated client value. */
    public String forStorage(int orgId, String value) {
        return hmac(STORAGE_DOMAIN, orgId, value);
    }

    /** Returns the organization-scoped bundle-safe HMAC of a legacy or current stored value. */
    public String forDisclosure(int orgId, String persistedValue) {
        return persistedValue == null ? null : hmac(DISCLOSURE_DOMAIN, orgId, persistedValue);
    }

    private String hmac(byte[] domain, int orgId, String value) {
        if (orgId <= 0) {
            throw new IllegalArgumentException("Organization id must be positive");
        }
        if (value == null) {
            throw new IllegalArgumentException("Correlation value is required");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.hmacSecretBytes(), HMAC_ALGORITHM));
            mac.update(domain);
            mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(orgId).array());
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is required for correlation pseudonymization",
                exception);
        }
    }
}
