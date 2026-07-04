package ooo.klae.connex.backend.sso;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.stereotype.Component;

/**
 * Generates a self-signed RSA service-provider signing credential for a SAML connection.
 * The SP signs its AuthnRequests with this key so IdPs that require signed requests (the
 * common enterprise default — Keycloak, Shibboleth, HENNGE) accept them; the certificate
 * is published in the SP metadata and handed to the IdP. Generated once per connection and
 * then persisted — the private key encrypted at rest, the certificate public.
 */
@Component
public class SamlSpCredentialFactory {

    private static final int KEY_SIZE = 2048;
    private static final long VALIDITY_DAYS = 3650;

    /**
     * Generates a fresh self-signed SP signing key pair and certificate.
     * @param commonName the certificate subject/issuer common name
     * @return the Base64 PKCS#8 private key and PEM certificate
     */
    public SamlSpKeyMaterial generate(String commonName) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE, new SecureRandom());
            KeyPair keyPair = generator.generateKeyPair();

            X500Name name = new X500Name("CN=" + commonName);
            Instant now = Instant.now();
            Date notBefore = Date.from(now.minus(1, ChronoUnit.HOURS));
            Date notAfter = Date.from(now.plus(VALIDITY_DAYS, ChronoUnit.DAYS));
            BigInteger serial = new BigInteger(64, new SecureRandom());

            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
            X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                    new JcaX509v3CertificateBuilder(name, serial, notBefore, notAfter, name,
                            keyPair.getPublic()).build(signer));

            String privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
            return new SamlSpKeyMaterial(privateKeyBase64, toPem(certificate.getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate SAML SP signing credential", e);
        }
    }

    private static String toPem(byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----\n";
    }
}
