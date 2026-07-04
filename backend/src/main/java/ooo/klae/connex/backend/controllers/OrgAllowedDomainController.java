package ooo.klae.connex.backend.controllers;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.AddAllowedDomainRequest;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.OrgAllowedDomainService;

/**
 * Organization email-domain allowlist administration (#316, Option B): the org-level ceiling that
 * constrains which domains any workspace in the org may invite. Org admin/owner gated in the service.
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/allowed-domains")
@RequiredArgsConstructor
public class OrgAllowedDomainController {

    private final OrgAllowedDomainService orgAllowedDomainService;
    private final AuthService authService;

    @GetMapping
    public List<String> list(@PathVariable int orgId) {
        return orgAllowedDomainService.listDomains(orgId, authService.getCurrentUser().getId());
    }

    @PostMapping
    public List<String> add(@PathVariable int orgId, @Valid @RequestBody AddAllowedDomainRequest request) {
        return orgAllowedDomainService.addDomain(orgId, authService.getCurrentUser().getId(), request.getDomain());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable int orgId, @RequestParam String domain) {
        orgAllowedDomainService.removeDomain(orgId, authService.getCurrentUser().getId(), domain);
    }
}
