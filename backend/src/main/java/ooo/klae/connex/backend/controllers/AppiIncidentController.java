package ooo.klae.connex.backend.controllers;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.AppiIncidentDto;
import ooo.klae.connex.backend.dto.AppiIncidentRequest;
import ooo.klae.connex.backend.dto.AppiIncidentScopeDto;
import ooo.klae.connex.backend.services.AppiIncidentService;
import ooo.klae.connex.backend.services.AuthService;

@RestController
@RequestMapping("/api/orgs/{orgId}/appi-incidents")
@RequiredArgsConstructor
public class AppiIncidentController {
    private final AppiIncidentService appiIncidentService;
    private final AuthService authService;

    @GetMapping
    public List<AppiIncidentDto> list(@PathVariable int orgId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return appiIncidentService.list(orgId, authService.getCurrentUser().getId(), limit, offset);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AppiIncidentDto create(@PathVariable int orgId, @Valid @RequestBody AppiIncidentRequest request) {
        return appiIncidentService.create(orgId, authService.getCurrentUser().getId(), request);
    }

    @GetMapping("/{incidentId}")
    public AppiIncidentDto get(@PathVariable int orgId, @PathVariable long incidentId) {
        return appiIncidentService.get(orgId, incidentId, authService.getCurrentUser().getId());
    }

    @PutMapping("/{incidentId}")
    public AppiIncidentDto update(@PathVariable int orgId, @PathVariable long incidentId,
            @Valid @RequestBody AppiIncidentRequest request) {
        return appiIncidentService.update(orgId, incidentId, authService.getCurrentUser().getId(), request);
    }

    @GetMapping("/{incidentId}/scope")
    public List<AppiIncidentScopeDto> scope(@PathVariable int orgId, @PathVariable long incidentId) {
        return appiIncidentService.scope(orgId, incidentId, authService.getCurrentUser().getId());
    }
}
