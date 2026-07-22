package ooo.klae.connex.backend.controllers;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.DeliveryUnsubscribeDto;
import ooo.klae.connex.backend.services.DeliveryUnsubscribeService;

/**
 * Public unsubscribe endpoints. The token in the path is the only credential; the workspace and
 * recipient are resolved from the delivery row it identifies, never from the request body. These
 * routes are allowlisted in {@code SecurityConfig} as CSRF-exempt and unauthenticated.
 */
@RestController
@RequestMapping("/api/delivery/unsubscribe")
@RequiredArgsConstructor
@Validated
public class DeliveryUnsubscribeController {
    private final DeliveryUnsubscribeService deliveryUnsubscribeService;

    @GetMapping("/{token}")
    public DeliveryUnsubscribeDto preview(
            @Pattern(regexp = "[a-f0-9]{64}") @PathVariable String token) {
        return deliveryUnsubscribeService.preview(token);
    }

    @PostMapping("/{token}")
    public DeliveryUnsubscribeDto unsubscribe(
            @Pattern(regexp = "[a-f0-9]{64}") @PathVariable String token) {
        return deliveryUnsubscribeService.unsubscribe(token);
    }
}
