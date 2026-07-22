package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.ContactChannelConsentDto;
import ooo.klae.connex.backend.dto.ContactChannelConsentRequest;
import ooo.klae.connex.backend.services.ConsentService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Workspace-owned contact-channel consent endpoints for people. */
@RestController
@RequestMapping("/api/persons/{id}/consent")
@RequiredArgsConstructor
@Validated
public class ConsentController {
    private final ConsentService consentService;

    @GetMapping
    @RequirePermission(Permission.CONSENT_MANAGE)
    public List<ContactChannelConsentDto> get(@Positive @PathVariable int id) {
        return consentService.getForPerson(id);
    }

    @PutMapping
    @RequirePermission(Permission.CONSENT_MANAGE)
    public ContactChannelConsentDto set(
            @Positive @PathVariable int id,
            @Valid @RequestBody ContactChannelConsentRequest request) {
        return consentService.setForPerson(id, request);
    }
}
