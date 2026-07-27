package ooo.klae.connex.backend.controllers;

import java.nio.charset.StandardCharsets;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncWebRequest;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.TenantExportService;
import ooo.klae.connex.backend.services.TenantExportService.TenantExportDownload;
import ooo.klae.connex.backend.tenant.TenantLifecycleProperties;

/** Organization-admin tenant export API. */
@RestController
@RequestMapping("/api/orgs/{orgId}")
@RequiredArgsConstructor
public class TenantLifecycleController {
    private final TenantExportService tenantExportService;
    private final AuthService authService;
    private final TenantLifecycleProperties properties;

    /** Streams a machine-readable ZIP containing all workspace tenant data. */
    @GetMapping("/workspaces/{workspaceId}/export")
    public ResponseEntity<StreamingResponseBody> export(
            @PathVariable int orgId,
            @PathVariable int workspaceId,
            HttpServletRequest request) {
        TenantExportDownload download = tenantExportService.prepare(
            orgId,
            workspaceId,
            authService.getCurrentUser().getId());
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
    }

    private void configureAsyncLifecycle(
            HttpServletRequest request,
            TenantExportDownload download) {
        AsyncWebRequest asyncRequest = WebAsyncUtils.getAsyncManager(request).getAsyncWebRequest();
        asyncRequest.setTimeout(properties.getExportTimeout().toMillis());
        asyncRequest.addTimeoutHandler(download::closeIfNotStarted);
        asyncRequest.addErrorHandler(error -> download.closeIfNotStarted());
        asyncRequest.addCompletionHandler(download::closeIfNotStarted);
    }
}
