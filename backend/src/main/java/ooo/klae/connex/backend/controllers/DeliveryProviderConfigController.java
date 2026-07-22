package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.delivery.DeliveryProviderConfigService;
import ooo.klae.connex.backend.dto.DeliveryProviderConfigDto;
import ooo.klae.connex.backend.dto.DeliveryProviderConfigRequest;
import ooo.klae.connex.backend.dto.DeliveryWebhookTokenDto;

/**
 * Owner/admin delivery provider settings for the active workspace. The permission
 * ({@code WORKSPACE_SETTINGS}) and workspace scope are enforced in the service; credentials are
 * write-only and never returned.
 */
@RestController
@RequestMapping("/api/delivery/providers")
@RequiredArgsConstructor
@Validated
public class DeliveryProviderConfigController {

    private final DeliveryProviderConfigService deliveryProviderConfigService;

    @GetMapping
    public List<DeliveryProviderConfigDto> list() {
        return deliveryProviderConfigService.list();
    }

    @PutMapping
    public DeliveryProviderConfigDto save(@Valid @RequestBody DeliveryProviderConfigRequest request) {
        return deliveryProviderConfigService.save(request);
    }

    @PostMapping("/{channel}/webhook-token")
    public DeliveryWebhookTokenDto issueWebhookToken(
            @Pattern(regexp = "[a-z]{1,16}") @PathVariable String channel) {
        return deliveryProviderConfigService.issueWebhookToken(channel);
    }

    @DeleteMapping("/{channel}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@Pattern(regexp = "[a-z]{1,16}") @PathVariable String channel) {
        deliveryProviderConfigService.delete(channel);
    }
}
