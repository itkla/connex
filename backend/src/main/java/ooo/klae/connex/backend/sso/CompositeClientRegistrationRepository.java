package ooo.klae.connex.backend.sso;

import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * The single {@link ClientRegistrationRepository} the OAuth2 login filter resolves against.
 * Consumer social-login ids ({@code google} / {@code microsoft}) come from the static
 * {@link SocialLoginClientRegistrations}; everything else ({@code org-<id>}) delegates to the
 * dynamic per-organization {@link DbClientRegistrationRepository}. This lets one filter chain
 * serve both social login and enterprise SSO.
 */
@Component
@Primary
@RequiredArgsConstructor
public class CompositeClientRegistrationRepository implements ClientRegistrationRepository {

    private final SocialLoginClientRegistrations socialLoginClientRegistrations;
    private final DbClientRegistrationRepository dbClientRegistrationRepository;

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        if (socialLoginClientRegistrations.isSocialRegistration(registrationId)) {
            return socialLoginClientRegistrations.find(registrationId);
        }
        return dbClientRegistrationRepository.findByRegistrationId(registrationId);
    }
}
