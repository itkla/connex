package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.SuppressionEntryDto;
import ooo.klae.connex.backend.dto.SuppressionEntryRequest;
import ooo.klae.connex.backend.dto.ContactSuppressionPageDto;
import ooo.klae.connex.backend.services.SuppressionService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Workspace-owned contact-channel suppression endpoints. */
@RestController
@RequestMapping("/api/suppressions")
@RequiredArgsConstructor
@Validated
public class SuppressionController {
    private final SuppressionService suppressionService;

    @GetMapping
    @RequirePermission(Permission.CONSENT_MANAGE)
    public List<SuppressionEntryDto> list() {
        return suppressionService.list();
    }

    /** Returns a bounded, minimized page of restrictions matching one owned contact. */
    @GetMapping("/persons/{personId}")
    @RequirePermission(Permission.CONSENT_MANAGE)
    public ContactSuppressionPageDto listForPerson(@Positive @PathVariable int personId,
            @PositiveOrZero @RequestParam(defaultValue = "0") int afterId) {
        return suppressionService.listForPerson(personId, afterId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.CONSENT_MANAGE)
    public SuppressionEntryDto add(@Valid @RequestBody SuppressionEntryRequest request) {
        return suppressionService.add(request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(Permission.CONSENT_MANAGE)
    public void remove(@Positive @PathVariable int id) {
        suppressionService.remove(id);
    }
}
