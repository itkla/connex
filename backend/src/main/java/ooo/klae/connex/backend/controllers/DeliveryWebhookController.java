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

import ooo.klae.connex.backend.delivery.DeliveryProviderException;
import ooo.klae.connex.backend.services.DeliveryWebhookService;

/**
 * Public delivery-provider webhook endpoint. The provider id and opaque token in the path are the
 * only credentials; the workspace and provider are resolved from the token, and the raw body is
 * signature-verified before anything is applied — nothing is trusted from the body. This route is
 * allowlisted in {@code SecurityConfig} as CSRF-exempt and unauthenticated. A bad token, provider
 * mismatch, or signature failure returns 400 with no body; a valid or replayed delivery returns 200.
 */
@RestController
@RequestMapping("/api/delivery/webhooks")
@RequiredArgsConstructor
@Validated
public class DeliveryWebhookController {

    private final DeliveryWebhookService deliveryWebhookService;

    @PostMapping("/{provider}/{token}")
    public ResponseEntity<Void> ingest(
            @Pattern(regexp = "[a-z0-9_]{1,32}") @PathVariable String provider,
            @Pattern(regexp = "[a-f0-9]{64}") @PathVariable String token,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        try {
            deliveryWebhookService.ingest(provider, token, body == null ? new byte[0] : body, headers(request));
            return ResponseEntity.ok().build();
        } catch (DeliveryProviderException exception) {
            return ResponseEntity.badRequest().build();
        }
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
