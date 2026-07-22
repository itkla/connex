package ooo.klae.connex.backend.sso;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.SsoConnection;
import ooo.klae.connex.backend.mappers.SsoConnectionMapper;

/**
 * Resolves a Spring Security {@link RelyingPartyRegistration} for a per-organization SAML
 * connection at login time — the SAML counterpart of {@link DbClientRegistrationRepository}.
 * The registration id encodes the organization as {@code org-<id>}; the connection is looked
 * up by that org and a registration is built either from stored IdP metadata XML or from the
 * explicit entityId/SSO-URL/certificate fields. Only enabled SAML connections resolve —
 * anything else (missing, disabled, OIDC, malformed certificate/metadata) returns null so the
 * SAML machinery treats the id as unknown rather than surfacing a 500.
 *
 * <p>The AuthnRequest uses the {@link Saml2MessageBinding#REDIRECT} binding so the SP-initiated
 * request is a redirect, not a POST auto-submit form — the latter would be blocked by the strict
 * {@code form-action 'none'} content-security policy in {@code SecurityConfig}.
 *
 * <p><strong>Deployment note:</strong> the SAML assertion-consumer endpoint receives a cross-site
 * top-level POST from the IdP, so the session cookie must be sent on that navigation. The default
     * session cookie is {@code SameSite=Lax} (kept as-is for OIDC/password/dev over plain HTTP); a
     * deployment that actually enables SAML must set {@code CONNEX_SESSION_COOKIE_SAME_SITE=none} together
     * with {@code CONNEX_SESSION_COOKIE_SECURE=true} and end-to-end HTTPS, otherwise the stashed AuthnRequest
     * cannot be matched on the round-trip. This is a per-environment deploy setting, not a global code change.
 */
@Component
@RequiredArgsConstructor
public class DbRelyingPartyRegistrationRepository implements RelyingPartyRegistrationRepository {

    private static final Logger log = LoggerFactory.getLogger(DbRelyingPartyRegistrationRepository.class);

    private static final String REGISTRATION_PREFIX = "org-";
    private static final String SP_ENTITY_ID_TEMPLATE =
            "{baseUrl}/api/saml2/service-provider-metadata/{registrationId}";
    private static final String SP_ACS_LOCATION_TEMPLATE =
            "{baseUrl}/api/login/saml2/sso/{registrationId}";

    private final SsoConnectionMapper ssoConnectionMapper;
    private final SsoSecretCipher ssoSecretCipher;

    @Override
    public RelyingPartyRegistration findByRegistrationId(String registrationId) {
        Integer orgId = parseOrgId(registrationId);
        if (orgId == null) {
            return null;
        }
        SsoConnection connection = ssoConnectionMapper.findByOrg(orgId);
        if (connection == null || !connection.isEnabled() || !"saml".equals(connection.getProtocol())) {
            return null;
        }
        RelyingPartyRegistration registration = build(registrationId, connection);
        if (registration == null) {
            return null;
        }
        return registration;
    }

    /**
     * Kept as a compatibility hook for callers that save SSO settings.
     * @param orgId the organization whose cached registration is stale
     */
    public void evict(int orgId) {
    }

    private RelyingPartyRegistration build(String registrationId, SsoConnection connection) {
        try {
            Saml2X509Credential spSigning = resolveSpSigningCredential(connection);
            String metadataXml = connection.getSamlIdpMetadataXml();
            if (metadataXml != null && !metadataXml.isBlank()) {
                RelyingPartyRegistration.Builder builder = RelyingPartyRegistrations
                        .fromMetadata(new ByteArrayInputStream(metadataXml.getBytes(StandardCharsets.UTF_8)))
                        .registrationId(registrationId)
                        .entityId(SP_ENTITY_ID_TEMPLATE)
                        .assertionConsumerServiceLocation(SP_ACS_LOCATION_TEMPLATE)
                        .assertingPartyMetadata(ap -> ap
                                .singleSignOnServiceBinding(Saml2MessageBinding.REDIRECT));
                if (spSigning != null) {
                    builder.signingX509Credentials(c -> c.add(spSigning));
                }
                return builder.build();
            }
            X509Certificate certificate = parseCertificate(connection.getSamlIdpX509());
            if (certificate == null) {
                log.warn("SAML connection for org {} has no valid IdP certificate; skipping", connection.getOrgId());
                return null;
            }
            RelyingPartyRegistration.Builder builder = RelyingPartyRegistration.withRegistrationId(registrationId)
                    .entityId(SP_ENTITY_ID_TEMPLATE)
                    .assertionConsumerServiceLocation(SP_ACS_LOCATION_TEMPLATE)
                    .assertingPartyMetadata(ap -> ap
                            .entityId(connection.getSamlIdpEntityId())
                            .singleSignOnServiceLocation(connection.getSamlSsoUrl())
                            .singleSignOnServiceBinding(Saml2MessageBinding.REDIRECT)
                            .wantAuthnRequestsSigned(spSigning != null)
                            .verificationX509Credentials(c -> c.add(Saml2X509Credential.verification(certificate))));
            if (spSigning != null) {
                builder.signingX509Credentials(c -> c.add(spSigning));
            }
            return builder.build();
        } catch (RuntimeException e) {
            log.warn("Failed to build SAML relying-party registration for org {}: {}",
                    connection.getOrgId(), e.getMessage());
            return null;
        }
    }

    private Saml2X509Credential resolveSpSigningCredential(SsoConnection connection) {
        if (connection.getSamlSpPrivateKeyEnc() == null || connection.getSamlSpCertificate() == null) {
            return null;
        }
        X509Certificate certificate = parseCertificate(connection.getSamlSpCertificate());
        PrivateKey privateKey = parsePrivateKey(ssoSecretCipher.decryptSamlSpPrivateKey(connection.getOrgId(),
                connection.getSamlSpPrivateKeyEnc()));
        if (certificate == null || privateKey == null) {
            return null;
        }
        return Saml2X509Credential.signing(privateKey, certificate);
    }

    private static PrivateKey parsePrivateKey(String base64Pkcs8) {
        try {
            byte[] der = Base64.getDecoder().decode(base64Pkcs8);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            return null;
        }
    }

    private static X509Certificate parseCertificate(String pem) {
        if (pem == null || pem.isBlank()) {
            return null;
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        } catch (CertificateException e) {
            return null;
        }
    }

    private static Integer parseOrgId(String registrationId) {
        if (registrationId == null || !registrationId.startsWith(REGISTRATION_PREFIX)) {
            return null;
        }
        try {
            return Integer.valueOf(registrationId.substring(REGISTRATION_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
