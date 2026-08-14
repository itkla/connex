package ooo.klae.connex.backend.services;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.FederatedIdentity;
import ooo.klae.connex.backend.beans.SsoLinkChallenge;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.mappers.FederatedIdentityMapper;
import ooo.klae.connex.backend.mappers.SsoLinkChallengeMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.util.ClientIpResolver.ResolvedClientIp;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/**
 * Drives the SSO account-linking flow: when a verified IdP email collides with an
 * existing password account, the account is never auto-linked. Instead a single-use
 * challenge is minted ({@link #createChallenge}) and the user must re-enter their
 * password once ({@link #confirmByHash}) to prove ownership before the federated identity
 * is written and a session is established. Only the SHA-256 hash of the raw challenge is
 * persisted; the raw challenge is exchanged directly into the browser's purpose-bound flow and
 * never enters a redirect URL. The confirm path is an online password oracle, so it is
 * IP-rate-limited and never establishes a session on any failure path.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class SsoLinkService {

    private final SsoLinkChallengeMapper ssoLinkChallengeMapper;
    private final FederatedIdentityMapper federatedIdentityMapper;
    private final TenantLifecycleControlMapper tenantLifecycleControlMapper;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter loginRateLimiter;
    private final AuthService authService;
    private final AuditService auditService;

    @Value("${connex.sso.link-challenge-expiry-minutes:15}")
    private int challengeExpiryMinutes;

    /**
     * Mints a single-use linking challenge for a link-required outcome, invalidating any
     * prior outstanding challenges for the same account. Stores only the token hash; the
     * returned raw token is immediately exchanged for a server-side browser flow and is never
     * stored or placed in the redirect URL.
     * @param request the link-required resolution carrying the account and IdP identity
     * @return the raw, unhashed token for immediate in-handler exchange
     */
    public String createChallenge(SsoLoginResult.LinkRequired request) {
        User user = userMapper.getUserByIdForShare(request.existingUserId());
        if (user == null) {
            throw new ResourceNotFoundException("This link is invalid or has expired");
        }
        requireActiveOrganization(request.orgId());
        if (user.getPassword() == null) {
            throw new ResourceNotFoundException("This link is invalid or has expired");
        }
        requireCompatibleIdentity(
            request.provider(),
            request.issuer(),
            request.subject(),
            request.existingUserId(),
            request.orgId());
        ssoLinkChallengeMapper.invalidateForUser(request.existingUserId());

        String rawToken = OneTimeTokenDigest.generate();
        SsoLinkChallenge challenge = new SsoLinkChallenge();
        challenge.setTokenHash(OneTimeTokenDigest.sha256(rawToken));
        challenge.setUserId(request.existingUserId());
        challenge.setProvider(request.provider());
        challenge.setIssuer(request.issuer());
        challenge.setExternalSubject(request.subject());
        challenge.setOrgId(request.orgId());
        ssoLinkChallengeMapper.insert(challenge, challengeExpiryMinutes);
        return rawToken;
    }

    /**
     * Confirms ownership of the challenged account by verifying the supplied password, then
     * links the IdP identity and establishes a session. Rate-limited per client IP and per
     * challenged account because it is an online password oracle. Nothing is written and no
     * session is established on any failure:
     * a missing/consumed/expired challenge is a non-enumerating 404, a wrong password records a
     * limiter failure and is a 401 that leaves the challenge redeemable.
     * @param rawToken the raw token from the linking redirect
     * @param password the account's current password, to prove ownership
     * @param clientIp the resolved client IP and trusted-proxy provenance, for rate limiting
     * @param httpRequest the current request
     * @param httpResponse the current response
     * @return the now-linked, signed-in user
     * @throws TooManyRequestsException when the client IP is over the failure cap
     * @throws ResourceNotFoundException when the challenge is missing, consumed, or expired
     * @throws ForbiddenException when the challenged organization is being removed
     * @throws BadCredentialsException when the account has no password or the password is wrong
     */
    public User confirm(String rawToken, String password, ResolvedClientIp clientIp,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String tokenHash = rawToken == null ? null : OneTimeTokenDigest.sha256(rawToken);
        return confirmByHash(tokenHash, password, clientIp, httpRequest, httpResponse);
    }

    /** @return whether the purpose-bound source digest names an active linking challenge */
    public boolean validateChallengeHash(String tokenHash) {
        return tokenHash != null && ssoLinkChallengeMapper.findByTokenHash(tokenHash) != null;
    }

    /** Confirms account ownership through a purpose-bound browser-flow source digest. */
    public User confirmByHash(String tokenHash, String password, ResolvedClientIp clientIp,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        long now = System.currentTimeMillis();
        if (loginRateLimiter.isBlockedForClient(clientIp, null, now)) {
            throw new TooManyRequestsException("Too many attempts. Please try again later.");
        }

        SsoLinkChallenge challenge = tokenHash == null ? null
                : ssoLinkChallengeMapper.findByTokenHash(tokenHash);
        if (challenge == null) {
            throw new ResourceNotFoundException("This link is invalid or has expired");
        }

        User user = userMapper.getUserByIdForShare(challenge.getUserId());
        if (user == null) {
            throw new ResourceNotFoundException("This link is invalid or has expired");
        }
        requireActiveOrganization(challenge.getOrgId());
        SsoLinkChallenge current = ssoLinkChallengeMapper.lockByTokenHash(
            challenge.getTokenHash());
        if (!challenge.equals(current)) {
            throw new ResourceNotFoundException("This link is invalid or has expired");
        }
        String username = user.getUsername();
        if (loginRateLimiter.isBlockedForClient(clientIp, username, now)) {
            throw new TooManyRequestsException("Too many attempts. Please try again later.");
        }
        if (user.getPassword() == null || !passwordEncoder.matches(password, user.getPassword())) {
            loginRateLimiter.recordFailureForClient(clientIp, username, now);
            throw new BadCredentialsException("Incorrect password");
        }

        if (ssoLinkChallengeMapper.markConsumed(challenge.getId()) == 0) {
            throw new ResourceNotFoundException("This link is invalid or has expired");
        }

        loginRateLimiter.recordSuccess(username);
        if (linkIdentity(challenge, user.getId())) {
            recordFederatedLink(challenge, user);
        }
        User authenticatedUser = authService.establishAuthenticatedSession(user, httpRequest, httpResponse);
        recordFederatedLinkLogin(challenge, authenticatedUser);
        return authenticatedUser;
    }

    private void requireActiveOrganization(Integer orgId) {
        if (orgId == null) {
            return;
        }
        if (tenantLifecycleControlMapper.lockActiveOrganizationForShare(orgId) == null) {
            throw new ForbiddenException("This organization is not accepting sign-ins");
        }
    }

    private boolean linkIdentity(SsoLinkChallenge challenge, int userId) {
        FederatedIdentity existing = federatedIdentityMapper.findByProviderIssuerSubject(
                challenge.getProvider(), challenge.getIssuer(), challenge.getExternalSubject());
        if (existing != null) {
            if (existing.getUserId() != userId
                    || !java.util.Objects.equals(existing.getOrgId(), challenge.getOrgId())) {
                throw new ForbiddenException("This federated identity is already linked");
            }
            return false;
        }
        FederatedIdentity link = new FederatedIdentity();
        link.setUserId(userId);
        link.setOrgId(challenge.getOrgId());
        link.setProvider(challenge.getProvider());
        link.setIssuer(challenge.getIssuer());
        link.setExternalSubject(challenge.getExternalSubject());
        federatedIdentityMapper.insert(link);
        return true;
    }

    private void requireCompatibleIdentity(
            String provider,
            String issuer,
            String subject,
            int userId,
            Integer orgId) {
        FederatedIdentity existing = federatedIdentityMapper.findByProviderIssuerSubject(
            provider,
            issuer,
            subject);
        if (existing != null
                && (existing.getUserId() != userId
                    || !java.util.Objects.equals(existing.getOrgId(), orgId))) {
            throw new ForbiddenException("This federated identity is already linked");
        }
    }

    private static Map<String, Object> federationContext(SsoLinkChallenge challenge) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("provider", challenge.getProvider());
        if (challenge.getOrgId() != null) {
            context.put("orgId", challenge.getOrgId());
        }
        return context;
    }

    private void recordFederatedLink(SsoLinkChallenge challenge, User user) {
        if (challenge.getOrgId() != null) {
            auditService.record("org.federated_identity.link", "organization", challenge.getOrgId(),
                    user.getDisplayName(), "Federated identity linked",
                    Map.of("userId", user.getId(), "provider", challenge.getProvider()));
            return;
        }
        auditService.record("auth.federated_identity.link", "user", user.getId(), user.getDisplayName(),
                "Federated identity linked", federationContext(challenge));
    }

    private void recordFederatedLinkLogin(SsoLinkChallenge challenge, User user) {
        if (challenge.getOrgId() != null) {
            auditService.record("org.login.federated_link", "organization", challenge.getOrgId(),
                    user.getDisplayName(), user.getDisplayName() + " logged in after SSO link",
                    Map.of("userId", user.getId(), "provider", challenge.getProvider()));
            return;
        }
        auditService.record("auth.login.federated_link", "user", user.getId(), user.getDisplayName(),
                user.getDisplayName() + " logged in after social link", federationContext(challenge));
    }

}
