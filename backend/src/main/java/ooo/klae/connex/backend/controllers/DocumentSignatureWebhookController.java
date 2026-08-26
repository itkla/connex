package ooo.klae.connex.backend.controllers;

import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.services.DocumentSignatureWebhookService;

/** Public provider-authenticated callback surface for document-signature adapters. */
@RestController
@RequestMapping("/api/document-signature/webhooks")
@RequiredArgsConstructor
@Validated
public class DocumentSignatureWebhookController {
    private final DocumentSignatureWebhookService webhookService;

    @PostMapping("/{provider}")
    public ResponseEntity<Void> ingest(
            @Pattern(regexp = "[a-z0-9_]{1,32}") @PathVariable String provider,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest servletRequest) {
        webhookService.ingest(
            provider,
            headers(servletRequest),
            body == null ? new byte[0] : body);
        return ResponseEntity.ok().build();
    }

    private static Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        return headers;
    }
}
