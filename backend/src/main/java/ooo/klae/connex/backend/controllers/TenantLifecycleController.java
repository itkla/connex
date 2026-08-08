package ooo.klae.connex.backend.controllers;

import java.nio.charset.StandardCharsets;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncWebRequest;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.TenantExportGrantResponse;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.TenantExportGrantService;
import ooo.klae.connex.backend.services.TenantExportGrantService.TenantExportGrant;
import ooo.klae.connex.backend.services.TenantExportService;
import ooo.klae.connex.backend.services.TenantExportService.TenantExportDownload;
import ooo.klae.connex.backend.tenant.TenantExportGrantCookie;

/** Organization-admin tenant export API. */
@RestController
@RequestMapping("/api/orgs/{orgId}")
@RequiredArgsConstructor
public class TenantLifecycleController {
    private final TenantExportService tenantExportService;
    private final TenantExportGrantService tenantExportGrantService;
    private final TenantExportGrantCookie tenantExportGrantCookie;
    private final AuthService authService;

    /** Issues the path-scoped credential used to start a browser-native export download. */
    @PostMapping("/workspaces/{workspaceId}/export")
    public ResponseEntity<TenantExportGrantResponse> issueExportGrant(
            @PathVariable int orgId,
            @PathVariable int workspaceId,
            HttpServletRequest request,
            HttpServletResponse response) {
        TenantExportGrant grant = tenantExportGrantService.issue(
            orgId,
            workspaceId,
            authService.getCurrentUser().getId(),
            requireSessionId(request));
        tenantExportGrantCookie.set(
            response,
            orgId,
            workspaceId,
            grant.token(),
            TenantExportGrantService.GRANT_LIFETIME);
        String downloadPath = downloadPath(orgId, workspaceId);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new TenantExportGrantResponse(grant.expiresAt(), downloadPath));
    }

    /** Streams a machine-readable ZIP containing all workspace tenant data. */
    @GetMapping("/workspaces/{workspaceId}/export")
    public ResponseEntity<StreamingResponseBody> export(
            @PathVariable int orgId,
            @PathVariable int workspaceId,
            @CookieValue(
                name = TenantExportGrantCookie.NAME,
                required = false) String exportGrant,
            HttpServletRequest request,
            HttpServletResponse response) {
        int actorId = authService.getCurrentUser().getId();
        TenantExportDownload download;
        if (exportGrant == null) {
            download = tenantExportService.prepare(orgId, workspaceId, actorId);
        } else {
            download = tenantExportGrantService.redeem(
                orgId,
                workspaceId,
                actorId,
                requireSessionId(request),
                exportGrant);
            tenantExportGrantCookie.clear(response, orgId, workspaceId);
        }
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

    private static String requireSessionId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new ForbiddenException("Authenticated session is unavailable");
        }
        return session.getId();
    }

    private static String downloadPath(int orgId, int workspaceId) {
        return "/api/orgs/" + orgId + "/workspaces/" + workspaceId + "/export";
    }

    private void configureAsyncLifecycle(
            HttpServletRequest request,
            TenantExportDownload download) {
        AsyncWebRequest asyncRequest = WebAsyncUtils.getAsyncManager(request).getAsyncWebRequest();
        asyncRequest.setTimeout(download.remainingTimeoutMillis());
        asyncRequest.addTimeoutHandler(download::cancel);
        asyncRequest.addErrorHandler(error -> download.cancel());
        asyncRequest.addCompletionHandler(download::cancel);
    }
}
