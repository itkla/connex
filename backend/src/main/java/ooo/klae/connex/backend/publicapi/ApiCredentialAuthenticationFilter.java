package ooo.klae.connex.backend.publicapi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.publicapi.ApiCredentialService.AuthenticatedCredential;
import ooo.klae.connex.backend.publicapi.ApiCredentialService.RoutingBinding;
import ooo.klae.connex.backend.config.PrivilegedMfaEnforcementFilter;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.config.PrivilegedMfaRequestPolicy;
import ooo.klae.connex.backend.exceptions.PrivilegedMfaAttestationRequiredException;
import ooo.klae.connex.backend.exceptions.PrivilegedMfaEnrollmentRequiredException;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.DenialAuditRateLimiter;
import ooo.klae.connex.backend.services.PrivilegedAccountService;
import ooo.klae.connex.backend.services.PrivilegedMfaStanding;
import ooo.klae.connex.backend.util.ClientIpResolver;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import tools.jackson.databind.ObjectMapper;

/** Authenticates hash-only personal access tokens exclusively on the public API chain. */
public class ApiCredentialAuthenticationFilter extends OncePerRequestFilter {
    /** Authority granted only while a public credential retains at least one live scope. */
    public static final String PUBLIC_API_AUTHORITY = "PUBLIC_API_AUTHENTICATED";
    /** Request attribute carrying the authoritative placement resolved by this filter. */
    public static final String TENANT_BINDING_ATTRIBUTE =
        ApiCredentialAuthenticationFilter.class.getName() + ".TENANT_BINDING";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String MFA_DENIED_ACTION = "auth.mfa.policy.denied";
    private static final Logger log = LoggerFactory.getLogger(ApiCredentialAuthenticationFilter.class);

    private final ApiCredentialService apiCredentialService;
    private final ApiRateLimiter apiRateLimiter;
    private final ObjectMapper objectMapper;
    private final PrivilegedMfaProperties privilegedMfaProperties;
    private final PrivilegedAccountService privilegedAccountService;
    private final AuditService auditService;
    private final DenialAuditRateLimiter denialAuditRateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final TenantCatalogResolver tenantCatalogResolver;
    private final TenantContext tenantContext;
    private final TenantWorkScope tenantWorkScope;
    private final RouteTransactionModeResolver routeTransactionModeResolver;
    private final TransactionTemplate readTransactionTemplate;

    /** Creates the public API authentication boundary. */
    public ApiCredentialAuthenticationFilter(
            ApiCredentialService apiCredentialService,
            ApiRateLimiter apiRateLimiter,
            ObjectMapper objectMapper,
            PrivilegedMfaProperties privilegedMfaProperties,
            PrivilegedAccountService privilegedAccountService,
            AuditService auditService,
            DenialAuditRateLimiter denialAuditRateLimiter,
            ClientIpResolver clientIpResolver,
            TenantCatalogResolver tenantCatalogResolver,
            TenantContext tenantContext,
            TenantWorkScope tenantWorkScope,
            RouteTransactionModeResolver routeTransactionModeResolver,
            PlatformTransactionManager transactionManager) {
        this(
            apiCredentialService,
            apiRateLimiter,
            objectMapper,
            privilegedMfaProperties,
            privilegedAccountService,
            auditService,
            denialAuditRateLimiter,
            clientIpResolver,
            tenantCatalogResolver,
            tenantContext,
            tenantWorkScope,
            routeTransactionModeResolver,
            readTransactionTemplate(transactionManager));
    }

    ApiCredentialAuthenticationFilter(
            ApiCredentialService apiCredentialService,
            ApiRateLimiter apiRateLimiter,
            ObjectMapper objectMapper,
            PrivilegedMfaProperties privilegedMfaProperties,
            PrivilegedAccountService privilegedAccountService,
            AuditService auditService,
            DenialAuditRateLimiter denialAuditRateLimiter,
            ClientIpResolver clientIpResolver,
            TenantCatalogResolver tenantCatalogResolver,
            TenantContext tenantContext,
            TenantWorkScope tenantWorkScope,
            RouteTransactionModeResolver routeTransactionModeResolver,
            TransactionTemplate readTransactionTemplate) {
        this.apiCredentialService = apiCredentialService;
        this.apiRateLimiter = apiRateLimiter;
        this.objectMapper = objectMapper;
        this.privilegedMfaProperties = privilegedMfaProperties;
        this.privilegedAccountService = privilegedAccountService;
        this.auditService = auditService;
        this.denialAuditRateLimiter = denialAuditRateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.tenantCatalogResolver = tenantCatalogResolver;
        this.tenantContext = tenantContext;
        this.tenantWorkScope = tenantWorkScope;
        this.routeTransactionModeResolver = routeTransactionModeResolver;
        this.readTransactionTemplate = readTransactionTemplate;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Long credentialId = null;
        try {
            tenantContext.clear();
            String rawToken = bearerToken(request);
            ApiRateLimiter.Decision preAuthenticationRateLimit =
                apiRateLimiter.acquireBeforeAuthentication(
                    clientIpResolver.resolve(request), rawToken);
            if (!preAuthenticationRateLimit.allowed()) {
                rateLimited(request, response, preAuthenticationRateLimit);
                return;
            }
            if (rawToken == null) {
                invalidToken(request, response);
                return;
            }

            Optional<RoutingBinding> routing = apiCredentialService.resolveRoutingBinding(rawToken);
            if (routing.isEmpty()) {
                invalidToken(request, response);
                return;
            }
            RoutingBinding binding = routing.get();
            credentialId = binding.credentialId();
            String catalog;
            try {
                catalog = tenantCatalogResolver.resolveCatalog(binding.organizationId());
            } catch (ServiceUnavailableException exception) {
                PublicApiErrorAdvice.write(
                    objectMapper,
                    request,
                    response,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "public_api_unavailable",
                    "Public API is unavailable",
                    binding.credentialId());
                return;
            }
            var scopes = tenantWorkScope.prepareWorkspaceScopes(
                binding.workspaceId(), binding.organizationId(), catalog);
            if (scopes != null) {
                tenantContext.setWithWorkspaceScopes(binding.workspaceId(), binding.organizationId(),
                    binding.userId(), "public_api", catalog, scopes);
            } else {
                tenantContext.set(
                    binding.workspaceId(),
                    binding.organizationId(),
                    binding.userId(),
                    "public_api",
                    catalog);
            }
            request.setAttribute(
                TENANT_BINDING_ATTRIBUTE,
                new TenantBinding(
                    binding.credentialId(),
                    binding.workspaceId(),
                    binding.organizationId(),
                    binding.userId(),
                    catalog));
            Long authenticatedCredentialId;
            if (RouteTransactionMode.READ.equals(routeTransactionModeResolver.resolve(request))) {
                authenticatedCredentialId = readTransactionTemplate.execute(status ->
                    authenticateAndContinue(binding, request, response, chain));
            } else {
                AuthenticatedCredential authenticated = readTransactionTemplate.execute(status ->
                    authenticateAndAuthorize(binding, request, response));
                if (authenticated == null) {
                    return;
                }
                authenticatedCredentialId = continueRequest(authenticated, request, response, chain);
            }
            if (authenticatedCredentialId != null) {
                recordSuccessfulUse(authenticatedCredentialId, binding.presentedHash());
            }
        } catch (PrivilegedMfaDenialException exception) {
            tenantContext.clear();
            recordDenial(request, exception);
            PublicApiErrorAdvice.write(objectMapper, request, response, HttpStatus.UNAUTHORIZED,
                exception.code, exception.getMessage());
        } catch (PrivilegedMfaEnrollmentRequiredException | PrivilegedMfaAttestationRequiredException exception) {
            PublicApiErrorAdvice.write(objectMapper, request, response, HttpStatus.UNAUTHORIZED,
                exception.getCode(), exception.getMessage());
        } catch (RequestRejectedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            unexpectedFailure(request, response, credentialId);
        } finally {
            request.removeAttribute(TENANT_BINDING_ATTRIBUTE);
            tenantContext.clear();
        }
    }

    private Long authenticateAndContinue(
            RoutingBinding routing,
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) {
        AuthenticatedCredential authenticated = authenticateAndAuthorize(routing, request, response);
        if (authenticated == null) {
            return null;
        }
        return continueRequest(authenticated, request, response, chain);
    }

    private AuthenticatedCredential authenticateAndAuthorize(
            RoutingBinding routing,
            HttpServletRequest request,
            HttpServletResponse response) {
        Optional<AuthenticatedCredential> resolved;
        try {
            resolved = apiCredentialService.authenticate(routing);
        } catch (RuntimeException exception) {
            throw new AuthenticationLookupException(exception);
        }
        if (resolved.isEmpty()) {
            writeInvalidToken(request, response);
            return null;
        }

        AuthenticatedCredential authenticated = resolved.get();
        PrivilegedMfaStanding standing;
        try {
            standing = privilegedMfaProperties.isEnforced()
                ? privilegedAccountService.enforcedStanding(authenticated.user().getId())
                : PrivilegedMfaStanding.UNPRIVILEGED;
        } catch (RuntimeException exception) {
            throw new AuthenticationLookupException(exception);
        }
        if (standing == PrivilegedMfaStanding.ENROLLMENT_REQUIRED) {
            throw new PrivilegedMfaDenialException(
                authenticated.user(),
                PrivilegedMfaEnforcementFilter.ENROLLMENT_REQUIRED_CODE,
                "Public API privileged account confined pending MFA enrollment",
                "enrollment_required",
                "A passkey must be enrolled before this privileged account can continue");
        }
        if (standing == PrivilegedMfaStanding.ATTESTATION_REQUIRED) {
            throw new PrivilegedMfaDenialException(
                authenticated.user(),
                PrivilegedMfaEnforcementFilter.ATTESTATION_REQUIRED_CODE,
                "Public API privileged account confined pending passkey approval",
                "attestation_required",
                "A passkey must be approved before this privileged account can continue");
        }
        ApiRateLimiter.Decision rateLimit = apiRateLimiter.acquire(
            authenticated.credential().credentialId());
        if (!rateLimit.allowed()) {
            writeRateLimited(
                request,
                response,
                rateLimit,
                authenticated.credential().credentialId());
            return null;
        }
        setRateLimitHeaders(response, rateLimit);
        if (authenticated.credential().authorizedScopes().isEmpty()) {
            writePublicError(
                request,
                response,
                HttpStatus.FORBIDDEN,
                "insufficient_scope",
                "The credential cannot access this resource",
                authenticated.credential().credentialId());
            return null;
        }
        return authenticated;
    }

    private Long continueRequest(
            AuthenticatedCredential authenticated,
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(PUBLIC_API_AUTHORITY));
        authenticated.credential().authorizedScopes().stream()
            .sorted()
            .map(ApiScope::authority)
            .map(SimpleGrantedAuthority::new)
            .forEach(authorities::add);
        PreAuthenticatedAuthenticationToken authentication =
            new PreAuthenticatedAuthenticationToken(authenticated.user(), null, authorities);
        authentication.setDetails(authenticated.credential());

        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext apiContext = SecurityContextHolder.createEmptyContext();
        apiContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(apiContext);
        PrivilegedMfaRequestPolicy.bindPublicApi(request, authenticated.user().getId());
        try {
            chain.doFilter(request, response);
            return authenticated.credential().credentialId();
        } catch (ServletException exception) {
            if (exception.getRootCause() instanceof PrivilegedMfaEnrollmentRequiredException refusal) {
                throw refusal;
            }
            if (exception.getRootCause() instanceof PrivilegedMfaAttestationRequiredException refusal) {
                throw refusal;
            }
            throw new FilterExecutionException(exception);
        } catch (IOException exception) {
            throw new FilterExecutionException(exception);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private void recordSuccessfulUse(long credentialId, String presentedHash) {
        try {
            apiCredentialService.recordSuccessfulUse(credentialId, presentedHash);
        } catch (RuntimeException exception) {
            log.warn("Failed to update public API credential usage credentialId={}", credentialId);
        }
    }

    /**
     * Appends only after the authentication transaction ends and its tenant binding is cleared,
     * so the independent account-scoped audit uses the control catalog. A failed append returns
     * its admission just as the browser filter does, while the request remains refused.
     * The shared limiter admits four fingerprints in each of eight address slots without extending
     * address windows. Without releases, it admits every address-only admission while windows are
     * retained. Releases preserve other fingerprints' evidence and their occupied address slot.
     */
    private void recordDenial(HttpServletRequest request, PrivilegedMfaDenialException denial) {
        User user = denial.user;
        String clientIp = clientIpResolver.resolveForDenialAudit(request);
        Optional<DenialAuditRateLimiter.Admission> admission = denialAuditRateLimiter.acquire(
            user.getId(), MFA_DENIED_ACTION, denial.reason, clientIp, request);
        if (admission.isEmpty()) {
            return;
        }
        boolean written = false;
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext auditContext = SecurityContextHolder.createEmptyContext();
        auditContext.setAuthentication(new PreAuthenticatedAuthenticationToken(user, null, List.of()));
        SecurityContextHolder.setContext(auditContext);
        try {
            auditService.recordStrictFailureIndependentScoped(MFA_DENIED_ACTION, "user", user.getId(),
                null, null, user.getDisplayName(), denial.summary, denial.reason, request, clientIp);
            written = true;
        } catch (RuntimeException exception) {
            log.error("Failed to record access denial action={} userId={}",
                MFA_DENIED_ACTION, user.getId(), exception);
        } finally {
            SecurityContextHolder.setContext(previous);
            if (!written) {
                denialAuditRateLimiter.release(admission.get());
            }
        }
    }

    private String bearerToken(HttpServletRequest request) {
        Enumeration<String> headers = request.getHeaders(HttpHeaders.AUTHORIZATION);
        if (headers == null || !headers.hasMoreElements()) {
            return null;
        }
        String authorization = headers.nextElement();
        if (headers.hasMoreElements()
                || authorization == null
                || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length());
        return token.isBlank() || !token.equals(token.trim()) ? null : token;
    }

    private void invalidToken(
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        PublicApiErrorAdvice.write(
            objectMapper,
            request,
            response,
            HttpStatus.UNAUTHORIZED,
            "invalid_token",
            "A valid bearer credential is required");
    }

    private void writeInvalidToken(
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            invalidToken(request, response);
        } catch (IOException exception) {
            throw new FilterExecutionException(exception);
        }
    }

    private void rateLimited(
            HttpServletRequest request,
            HttpServletResponse response,
            ApiRateLimiter.Decision rateLimit) throws IOException {
        PublicApiErrorAdvice.write(
            objectMapper,
            request,
            response,
            HttpStatus.TOO_MANY_REQUESTS,
            "rate_limit_exceeded",
            "Rate limit exceeded",
            rateLimitHeaders(rateLimit));
    }

    private void writeRateLimited(
            HttpServletRequest request,
            HttpServletResponse response,
            ApiRateLimiter.Decision rateLimit,
            long credentialId) {
        try {
            PublicApiErrorAdvice.write(
                objectMapper,
                request,
                response,
                HttpStatus.TOO_MANY_REQUESTS,
                "rate_limit_exceeded",
                "Rate limit exceeded",
                credentialId,
                rateLimitHeaders(rateLimit));
        } catch (IOException exception) {
            throw new FilterExecutionException(exception);
        }
    }

    private void writePublicError(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String message,
            long credentialId) {
        try {
            PublicApiErrorAdvice.write(
                objectMapper, request, response, status, code, message, credentialId);
        } catch (IOException exception) {
            throw new FilterExecutionException(exception);
        }
    }

    private void unexpectedFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            Long credentialId)
            throws IOException {
        if (!response.isCommitted()) {
            if (credentialId == null) {
                log.warn("Public API request failed before credential resolution");
            } else {
                log.warn("Public API request failed credentialId={}", credentialId);
            }
        }
        if (credentialId == null) {
            PublicApiErrorAdvice.write(
                objectMapper,
                request,
                response,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal_error",
                "An unexpected error occurred");
        } else {
            PublicApiErrorAdvice.write(
                objectMapper,
                request,
                response,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal_error",
                "An unexpected error occurred",
                credentialId);
        }
    }

    private static TransactionTemplate readTransactionTemplate(
            PlatformTransactionManager transactionManager) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transactionTemplate.setReadOnly(true);
        return transactionTemplate;
    }

    private static void setRateLimitHeaders(
            HttpServletResponse response, ApiRateLimiter.Decision rateLimit) {
        response.setHeader("X-RateLimit-Limit", Integer.toString(rateLimit.limit()));
        response.setHeader("X-RateLimit-Remaining", Integer.toString(rateLimit.remaining()));
        response.setHeader("X-RateLimit-Reset", Long.toString(rateLimit.resetAt()));
        if (!rateLimit.allowed()) {
            response.setHeader("Retry-After", Long.toString(rateLimit.retryAfterSeconds()));
        }
    }

    private static Map<String, String> rateLimitHeaders(ApiRateLimiter.Decision rateLimit) {
        return Map.of(
            "X-RateLimit-Limit", Integer.toString(rateLimit.limit()),
            "X-RateLimit-Remaining", Integer.toString(rateLimit.remaining()),
            "X-RateLimit-Reset", Long.toString(rateLimit.resetAt()),
            "Retry-After", Long.toString(rateLimit.retryAfterSeconds()));
    }

    private static final class PrivilegedMfaDenialException extends RuntimeException {
        private final User user;
        private final String code;
        private final String summary;
        private final String reason;

        private PrivilegedMfaDenialException(
                User user, String code, String summary, String reason, String message) {
            super(message);
            this.user = user;
            this.code = code;
            this.summary = summary;
            this.reason = reason;
        }
    }

    private static final class AuthenticationLookupException extends RuntimeException {
        AuthenticationLookupException(RuntimeException cause) {
            super(cause);
        }
    }

    private static final class FilterExecutionException extends RuntimeException {
        FilterExecutionException(Exception cause) {
            super(cause);
        }
    }

    /** Transaction posture selected by the complete public route registry. */
    public enum RouteTransactionMode {
        READ,
        WRITE
    }

    /** Resolves whether authentication may span the controller or must commit before dispatch. */
    @FunctionalInterface
    public interface RouteTransactionModeResolver {
        RouteTransactionMode resolve(HttpServletRequest request);
    }

    /** Authoritative tenant identity and catalog resolved before authentication begins. */
    public record TenantBinding(
            long credentialId,
            int workspaceId,
            int organizationId,
            int userId,
            String catalog) {
    }
}
