package ooo.klae.connex.backend.controllers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncWebRequest;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.SupportBundleService;
import ooo.klae.connex.backend.services.SupportBundleService.SupportBundleDownload;
import ooo.klae.connex.backend.services.SupportBundleService.SupportBundleRequest;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * Organization-admin redacted support bundle download.
 *
 * <p>Workspace record events are double-gated. They are collected only when an entity filter is
 * supplied, the resolved active workspace belongs to the requested organization, and the caller
 * holds {@code AUDIT_READ} in that workspace. Organization administration alone never unlocks
 * workspace record events, mirroring the boundary {@code OrgAuditController} keeps.
 */
@RestController
@RequestMapping("/api/orgs/{orgId}")
@RequiredArgsConstructor
public class SupportBundleController {
    private static final int ENTITY_TYPE_MAX = 32;

    private final SupportBundleService supportBundleService;
    private final AuthService authService;
    private final WorkspaceService workspaceService;
    private final TenantContext tenantContext;

    /**
     * Streams the redacted support bundle for one organization.
     *
     * @param orgId         the organization to collect for
     * @param correlationId the correlation id filter, or null
     * @param entityType    the record type filter, or null
     * @param entityId      the record id filter, or null
     * @param since         the ISO-8601 window start, or null for the default seven days
     * @param request       the servlet request driving the async lifecycle
     * @return the streaming ZIP response
     */
    @GetMapping("/support-bundle")
    public ResponseEntity<StreamingResponseBody> supportBundle(
            @PathVariable int orgId,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Integer entityId,
            @RequestParam(required = false) String since,
            HttpServletRequest request) {
        int actorId = authService.getCurrentUser().getId();
        Integer workspaceId = resolveEntityFilterWorkspace(orgId, entityType, entityId);
        SupportBundleRequest bundleRequest = new SupportBundleRequest(
            orgId,
            SupportBundleService.validateCorrelationId(correlationId),
            normalizeEntityType(entityType),
            entityId,
            workspaceId,
            parseSince(since));

        SupportBundleDownload download = supportBundleService.prepare(bundleRequest, actorId);
        try {
            configureAsyncLifecycle(request, download);
            StreamingResponseBody body = download::writeTo;
            ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.filename(), StandardCharsets.UTF_8)
                .build();
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .header(
                    "Content-Security-Policy",
                    "default-src 'none'; sandbox; frame-ancestors 'none'; base-uri 'none'")
                .body(body);
        } catch (RuntimeException | Error exception) {
            download.cancel();
            throw exception;
        }
    }

    private Integer resolveEntityFilterWorkspace(int orgId, String entityType, Integer entityId) {
        if (entityType == null && entityId == null) {
            return null;
        }
        if (entityType == null || entityId == null) {
            throw new BadRequestException("entityType and entityId must be supplied together");
        }
        if (entityId <= 0) {
            throw new BadRequestException("entityId must be positive");
        }
        Integer workspaceId = tenantContext.getWorkspaceId();
        if (workspaceId == null || workspaceId <= 0) {
            throw new BadRequestException(
                "An active workspace is required to filter by entity");
        }
        if (workspaceService.getCurrentWorkspace().getOrgId() != orgId) {
            throw new ResourceNotFoundException("Workspace not found in this organization");
        }
        workspaceService.requirePermission(Permission.AUDIT_READ);
        return workspaceId;
    }

    private static String normalizeEntityType(String entityType) {
        if (entityType == null) {
            return null;
        }
        if (!entityType.matches("^[a-z][a-z_]{0," + (ENTITY_TYPE_MAX - 1) + "}$")) {
            throw new BadRequestException("entityType is malformed");
        }
        return entityType;
    }

    private static Instant parseSince(String since) {
        if (since == null || since.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(since);
        } catch (DateTimeParseException exception) {
            throw new BadRequestException("since must be an ISO-8601 instant");
        }
    }

    private void configureAsyncLifecycle(
            HttpServletRequest request,
            SupportBundleDownload download) {
        AsyncWebRequest asyncRequest = WebAsyncUtils.getAsyncManager(request).getAsyncWebRequest();
        asyncRequest.setTimeout(download.remainingTimeoutMillis());
        asyncRequest.addTimeoutHandler(download::cancel);
        asyncRequest.addErrorHandler(error -> download.cancel());
        asyncRequest.addCompletionHandler(download::cancel);
    }
}
