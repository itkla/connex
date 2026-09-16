package ooo.klae.connex.backend.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.RequestPathNormalizer;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.observability.ClientAssertedCorrelationPseudonymizer;
import ooo.klae.connex.backend.observability.CorrelationIds;
import ooo.klae.connex.backend.publicapi.ApiCredentialAuthenticationFilter;
import ooo.klae.connex.backend.publicapi.ApiCredentialAuthenticationFilter.TenantBinding;
import ooo.klae.connex.backend.publicapi.ApiCredentialPrincipal;
import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * Resolves the active workspace once per authenticated request and stores it in
 * {@link TenantContext}. Precedence: {@code X-Workspace-Id} header, then the
 * {@code connex_workspace} cookie, then the user's raw remembered workspace id,
 * or first membership only when no workspace is remembered.
 * The candidate is always re-validated against membership, so a forged header or
 * cookie cannot grant access to a workspace the caller does not belong to.
 * Public API requests retain the authoritative workspace and catalog already
 * resolved by the credential filter and deliberately ignore both browser
 * selection mechanisms.
 *
 * <p>A stale matching cookie/header pair, cookie-only pin, or implicit remembered
 * selection with no header or cookie that fails membership after the caller was
 * removed from that workspace falls back on
 * {@code GET}, {@code HEAD}, and {@code OPTIONS} requests to
 * {@link WorkspaceService#defaultWorkspaceIdFor(int)} and rewrites the
 * workspace cookie so the next request stops targeting the revoked id (#1108).
 * Other methods return 403 before changing the selection, so a pending mutation
 * cannot be redirected into another workspace (#1649).
 * An explicit foreign {@code X-Workspace-Id} (header without that cookie, or
 * disagreeing with it) still returns 403 when the caller is not a member.
 * Only a membership the caller still holds is ever installed in {@link TenantContext}.
 *
 * <p>When no membership remains at all the request falls through <em>unresolved</em> for
 * every method, and the dead selection is forgotten — the cookie is cleared and
 * {@link WorkspaceService#forgetActive(int)} NULLs the remembered id. There is no workspace a
 * write could be redirected into, {@link TenantScopeInterceptor} still refuses every
 * workspace-scoped statement without a resolved scope, and refusing instead would lock the
 * caller out of {@code POST /api/workspaces} and invite acceptance — the only endpoints that
 * can give them a workspace again (#1649).
 *
 * <p>{@code SELECTION_PATH} lists the selection and link-bootstrap routes that are exempt from the
 * stale-candidate 403 and fall through unresolved instead: {@code POST /api/workspaces},
 * {@code POST /api/workspaces/{id}/switch|accept|decline|leave},
 * {@code POST /api/invites/exchange|accept}, {@code POST /api/invite-links/exchange|accept},
 * {@code POST /api/delivery/unsubscribe/exchange} and {@code POST /api/document-acceptance/exchange}.
 * Each authorizes its own path or bearer target independently of {@link TenantContext}; token
 * exchange must remain reachable before the client's first healing read. No fallback scope is
 * installed, and the endpoint's token, CSRF and admission checks still apply. Nothing else is exempt.
 *
 * <p>The write protection is scoped to the first request that observes the revocation. A safe
 * read heals the selection to a workspace the caller still belongs to, and subsequent writes go
 * to the healed workspace by design — that is the pre-existing #1108 contract, and the healed
 * cookie is what the client reads back to render the active workspace. Requiring an explicit
 * reselection for the next unsafe request instead would need per-client state the interceptor
 * does not have (the cookie is the client's only selection channel), so a composer that must
 * not follow a heal has to compare its origin workspace before submitting (frontend follow-up #1732).
 *
 * <p>{@link TenantContext} is a {@code ThreadLocal} on a pooled container thread,
 * so the scope's teardown is load-bearing for tenant isolation (#988). Two rules
 * keep a scope from outliving the request that installed it:
 *
 * <ul>
 *   <li>{@link #preHandle} retains only a public credential binding whose request
 *       marker, authenticated details, and live scope agree exactly. Every other
 *       path clears before returning, so an unresolved request can never inherit
 *       whatever the previous request on this thread left behind.</li>
 *   <li>This is an {@link AsyncHandlerInterceptor} because Spring dispatches
 *       {@link #afterConcurrentHandlingStarted} <em>instead of</em>
 *       {@link #afterCompletion} once a handler starts async processing, and only
 *       to interceptors of that type. A plain {@code HandlerInterceptor} therefore
 *       gets no teardown callback at all on the streaming endpoints and hands the
 *       thread back to the pool with the scope still installed.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class TenantResolutionInterceptor implements AsyncHandlerInterceptor {
    static final String ASYNC_JOURNAL_EXCLUDED_ATTRIBUTE =
        TenantResolutionInterceptor.class.getName() + ".ASYNC_JOURNAL_EXCLUDED";
    static final String ORGANIZATION_ID_ATTRIBUTE = TenantResolutionInterceptor.class.getName() + ".ORGANIZATION_ID";
    static final String JOURNAL_EVENT_CLASS = "http.request.completed";

    private static final Pattern WORKSPACE_LIFECYCLE_PATH = Pattern.compile(
        "/api/orgs/\\d+/workspaces/\\d+");
    private static final Pattern ORGANIZATION_LIFECYCLE_PATH = Pattern.compile(
        "/api/orgs/\\d+");
    private static final Set<String> WORKSPACE_RECOVERY_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Pattern SELECTION_PATH = Pattern.compile(
        "/api/workspaces|/api/workspaces/\\d+/(?:switch|accept|decline|leave)"
            + "|/api/invites/(?:exchange|accept)|/api/invite-links/(?:exchange|accept)"
            + "|/api/delivery/unsubscribe/exchange|/api/document-acceptance/exchange");
    private static final Set<String> JOURNAL_METHODS = Set.of(
        "DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT");
    private static final Logger log = LoggerFactory.getLogger(TenantResolutionInterceptor.class);

    private final WorkspaceService workspaceService;
    private final TenantContext tenantContext;
    private final TenantCatalogResolver tenantCatalogResolver;
    private final WorkspaceRequestResolver workspaceRequestResolver;
    private final WorkspaceCookie workspaceCookie;
    private final ClientAssertedCorrelationPseudonymizer correlationPseudonymizer;

    /**
     * Retains a validated public credential scope or discards any scope left on
     * this pooled thread before resolving an ordinary browser request.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.removeAttribute(ORGANIZATION_ID_ATTRIBUTE);
        if (isLifecycleRequest(request)) {
            tenantContext.clear();
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (workspaceRequestResolver.isPublicApiRequest(request)) {
            return retainPublicApiBinding(request, auth);
        }
        tenantContext.clear();
        if (auth == null || !(auth.getPrincipal() instanceof User user)) {
            return true;
        }

        Integer candidate = workspaceRequestResolver.resolve(request, user.getId());
        if (candidate == null) {
            return true;
        }

        String role = workspaceService.getRole(candidate, user.getId());
        if (role == null) {
            boolean selectionRequest = isSelectionRequest(request);
            if (!selectionRequest
                    && !workspaceRequestResolver.isStaleWorkspacePin(request, candidate)) {
                throw new ForbiddenException("Not a member of workspace " + candidate);
            }
            Integer fallback = workspaceService.defaultWorkspaceIdFor(user.getId());
            if (fallback == null) {
                forgetStaleSelection(user.getId(), response);
                return true;
            }
            if (selectionRequest) {
                return true;
            }
            if (!WORKSPACE_RECOVERY_METHODS.contains(request.getMethod())) {
                throw new ForbiddenException("Not a member of workspace " + candidate);
            }
            workspaceService.rememberActive(user.getId(), fallback);
            workspaceCookie.set(response, fallback);
            candidate = fallback;
            role = workspaceService.getRole(candidate, user.getId());
            if (role == null) {
                forgetStaleSelection(user.getId(), response);
                return true;
            }
        }

        int orgId = workspaceService.getOrgId(candidate);
        String catalog = tenantCatalogResolver.resolveCatalog(orgId);
        tenantContext.set(candidate, orgId, user.getId(), role, catalog);
        request.setAttribute(ORGANIZATION_ID_ATTRIBUTE, orgId);
        return true;
    }

    /**
     * Whether the request targets a selection or link-bootstrap route with independent target
     * authorization. Those routes fall through unresolved rather than 403 on a stale candidate,
     * so clients can exchange a bearer before their first healing read.
     */
    private static boolean isSelectionRequest(HttpServletRequest request) {
        return "POST".equals(request.getMethod())
            && SELECTION_PATH.matcher(RequestPathNormalizer.apiPath(request)).matches();
    }

    private void forgetStaleSelection(int userId, HttpServletResponse response) {
        workspaceCookie.clear(response);
        workspaceService.forgetActive(userId);
    }

    private boolean retainPublicApiBinding(
            HttpServletRequest request, Authentication authentication) {
        Object attribute = request.getAttribute(
            ApiCredentialAuthenticationFilter.TENANT_BINDING_ATTRIBUTE);
        User user = authentication != null && authentication.getPrincipal() instanceof User principal
            ? principal
            : null;
        ApiCredentialPrincipal credential = user == null
            ? null
            : workspaceRequestResolver.resolvePublicApiCredential(
                request, authentication, user.getId());
        if (!(attribute instanceof TenantBinding binding)
                || credential == null
                || binding.credentialId() != credential.credentialId()
                || binding.workspaceId() != credential.workspaceId()
                || binding.organizationId() != credential.organizationId()
                || binding.userId() != credential.userId()
                || !tenantContext.isResolved()
                || !Objects.equals(tenantContext.getWorkspaceId(), binding.workspaceId())
                || !Objects.equals(tenantContext.getOrgId(), binding.organizationId())
                || !Objects.equals(tenantContext.getUserId(), binding.userId())
                || !Objects.equals(tenantContext.getScopeCatalog(), binding.catalog())) {
            tenantContext.clear();
            throw new ForbiddenException("API credential tenant binding is unavailable");
        }
        request.setAttribute(ORGANIZATION_ID_ATTRIBUTE, binding.organizationId());
        return true;
    }

    private boolean isLifecycleRequest(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        return ("GET".equals(method)
                && path.endsWith("/export")
                && WORKSPACE_LIFECYCLE_PATH.matcher(
                    path.substring(0, path.length() - "/export".length())).matches())
            || ("DELETE".equals(method)
                && (WORKSPACE_LIFECYCLE_PATH.matcher(path).matches()
                    || ORGANIZATION_LIFECYCLE_PATH.matcher(path).matches()));
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        try {
            emitJournalRecord(request, response, handler);
        } finally {
            tenantContext.clear();
        }
    }

    /**
     * Releases the scope when the handler hands the response off to async processing
     * and the container thread returns to the pool, which is the one exit
     * {@link #afterCompletion} never observes.
     *
     * <p>Safe for the {@code StreamingResponseBody} endpoints: their tenant-scoped
     * work all completes on this thread before the body is returned, and the body
     * itself runs on a separate executor that never inherits this
     * {@code ThreadLocal}. Bodies that do need a scope install their own — the
     * tenant export re-pins its route through {@code TenantLifecycleAccess}.
     */
    @Override
    public void afterConcurrentHandlingStarted(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(ASYNC_JOURNAL_EXCLUDED_ATTRIBUTE, Boolean.TRUE);
        tenantContext.clear();
    }

    private void emitJournalRecord(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)
                || !journalAttributable(handlerMethod)
                || Boolean.TRUE.equals(request.getAttribute(ASYNC_JOURNAL_EXCLUDED_ATTRIBUTE))
                || !(request.getAttribute(ORGANIZATION_ID_ATTRIBUTE) instanceof Integer orgId)
                || orgId <= 0
                || !(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String path)
                || path.isBlank()
                || path.length() > 512
                || !JOURNAL_METHODS.contains(request.getMethod())
                || response.getStatus() < 100
                || response.getStatus() > 599) {
            return;
        }
        String correlationId = MDC.get(CorrelationIds.MDC_KEY);
        if (!CorrelationIds.isValid(correlationId)) {
            return;
        }
        String disclosureHmac = correlationPseudonymizer.forDisclosure(
            orgId, correlationPseudonymizer.forStorage(orgId, correlationId));
        MDC.remove(CorrelationIds.MDC_KEY);
        try {
            log.atInfo()
                .addKeyValue("connexOrganizationId", orgId)
                .addKeyValue("untrustedClientAssertedCorrelationHmac", disclosureHmac)
                .addKeyValue("requestMethod", request.getMethod())
                .addKeyValue("requestPath", path)
                .addKeyValue("responseStatus", response.getStatus())
                .addKeyValue("eventClass", JOURNAL_EVENT_CLASS)
                .log("Tenant request completed");
        } finally {
            MDC.put(CorrelationIds.MDC_KEY, correlationId);
        }
    }

    private static boolean journalAttributable(HandlerMethod handler) {
        return handler.hasMethodAnnotation(TenantJournalAttributable.class)
            || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), TenantJournalAttributable.class);
    }

}
