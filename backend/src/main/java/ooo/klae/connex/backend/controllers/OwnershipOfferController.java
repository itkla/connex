package ooo.klae.connex.backend.controllers;

import java.util.List;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.AcceptOwnershipOfferRequest;
import ooo.klae.connex.backend.dto.OwnershipOfferDto;
import ooo.klae.connex.backend.dto.OwnershipOfferAcceptanceDto;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.OwnershipOfferService;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Session-authenticated ownership consent; self routes do not depend on workspace selection. */
@RestController
@RequiredArgsConstructor
public class OwnershipOfferController {
    private final OwnershipOfferService service;
    private final AuthService authService;
    private final TenantWorkScope tenantWorkScope;

    /** Pending, live cards addressed only to the authenticated account. */
    @GetMapping("/api/ownership-offers")
    public List<OwnershipOfferDto> self() {
        return service.listForSelf(currentUserId());
    }

    /** Pending workspace offers, visible only to current workspace owners. */
    @GetMapping("/api/workspaces/{id}/ownership-offers")
    public List<OwnershipOfferDto> workspace(@PathVariable int id) {
        return service.listForScope("WORKSPACE", id, currentUserId());
    }

    /** Pending organization offers, visible only to current organization owners. */
    @GetMapping("/api/orgs/{id}/ownership-offers")
    public List<OwnershipOfferDto> organization(@PathVariable int id) {
        return service.listForScope("ORGANIZATION", id, currentUserId());
    }

    /** Accepts an offer with the offeree's recent authentication and optional confinement consent. */
    @PostMapping("/api/ownership-offers/{id}/accept")
    public OwnershipOfferAcceptanceDto accept(@PathVariable long id,
            @Valid @RequestBody(required = false) AcceptOwnershipOfferRequest request) {
        return service.accept(id, currentUserId(),
            request != null && Boolean.TRUE.equals(request.getAcknowledgeConfinement()));
    }

    /** Declining grants nothing and requires no step-up. */
    @PostMapping("/api/ownership-offers/{id}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@PathVariable long id) {
        service.decline(id, currentUserId());
    }

    /** Revokes workspace consent as any current workspace owner. */
    @DeleteMapping("/api/workspaces/{id}/ownership-offers/{offerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeWorkspace(@PathVariable int id, @PathVariable long offerId) {
        service.revoke("WORKSPACE", id, offerId, currentUserId());
    }

    /** Revokes organization consent as any current organization owner. */
    @DeleteMapping("/api/orgs/{id}/ownership-offers/{offerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeOrganization(@PathVariable int id, @PathVariable long offerId) {
        service.revoke("ORGANIZATION", id, offerId, currentUserId());
    }

    private int currentUserId() {
        return tenantWorkScope.unroutedRead(() -> authService.getCurrentUser().getId());
    }
}
