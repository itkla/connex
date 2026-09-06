package ooo.klae.connex.backend.mail;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Creates an isolated self-signed server TLS context for loopback transport tests.
 *
 * <p>Clients reach these relays through the mail library's own {@code mail.smtp.ssl.trust}
 * property, because the production sender deliberately publishes no SSL socket factory and lets
 * the mail library layer TLS with the JVM's default trust material.
 */
public final class TestTlsContexts {

    private TestTlsContexts() {
    }

    /**
     * Creates a server context presenting a freshly generated certificate for one DNS name.
     *
     * @param dnsName certificate DNS identity
     * @return the server context
     */
    public static SSLContext forServerName(String dnsName) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, new SecureRandom());
            KeyPair keyPair = generator.generateKeyPair();
            X500Name subject = new X500Name("CN=" + dnsName);
            Instant now = Instant.now();
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                    .build(keyPair.getPrivate());
            X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                    new JcaX509v3CertificateBuilder(
                            subject,
                            new BigInteger(64, new SecureRandom()),
                            Date.from(now.minus(1, ChronoUnit.HOURS)),
                            Date.from(now.plus(1, ChronoUnit.DAYS)),
                            subject,
                            keyPair.getPublic())
                            .addExtension(
                                    Extension.subjectAlternativeName,
                                    false,
                                    new GeneralNames(new GeneralName(
                                            GeneralName.dNSName, dnsName)))
                            .build(signer));
            char[] password = "loopback-test".toCharArray();
            KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType());
            keys.load(null, null);
            keys.setKeyEntry(
                    "server", keyPair.getPrivate(), password,
                    new Certificate[] {certificate});
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keys, password);

            SSLContext server = SSLContext.getInstance("TLS");
            server.init(keyManagers.getKeyManagers(), null, new SecureRandom());
            return server;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create the loopback TLS context", exception);
        }
    }
}
