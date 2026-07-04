package ooo.klae.connex.backend.sso;

/**
 * A freshly generated SAML service-provider signing credential: the private key as a
 * Base64-encoded PKCS#8 blob (to be encrypted at rest) and the self-signed certificate
 * as PEM (public, published in SP metadata).
 * @param privateKeyBase64 the Base64 PKCS#8 private key
 * @param certificatePem the PEM-encoded self-signed certificate
 */
public record SamlSpKeyMaterial(String privateKeyBase64, String certificatePem) {
}
