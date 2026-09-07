package ooo.klae.connex.backend.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.services.CspReportService;
import ooo.klae.connex.backend.util.ClientIpResolver;

/**
 * Unauthenticated, CSRF-exempt collector for browser Content Security Policy violation reports.
 */
@RestController
@RequestMapping("/api/csp-reports")
@RequiredArgsConstructor
public class CspReportController {
    private final CspReportService cspReportService;
    private final ClientIpResolver clientIpResolver;

    /**
     * Accepts one violation report request.
     *
     * <p>Always answers {@code 204} for a body the edge and application size filters admitted:
     * a browser cannot act on an error and a distinguishable response would only tell an
     * unauthenticated caller which payloads the collector parsed.
     *
     * @param body the raw report body, absent for an empty request
     * @param request the inbound request, read only for its client address
     */
    @PostMapping(consumes = {
            "application/csp-report",
            "application/reports+json",
            MediaType.APPLICATION_JSON_VALUE})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@RequestBody(required = false) String body, HttpServletRequest request) {
        cspReportService.ingest(body, clientIpResolver.resolveWithProvenance(request));
    }
}
