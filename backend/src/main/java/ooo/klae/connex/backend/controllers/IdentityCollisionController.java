package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.IdentityCollisionDto;
import ooo.klae.connex.backend.dto.IdentityCollisionQuery;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.services.IdentityCollisionService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Read-only HTTP surface for the workspace identity collision report.
 */
@RestController
@RequestMapping("/api/identity-collisions")
@RequiredArgsConstructor
public class IdentityCollisionController {

    private final IdentityCollisionService identityCollisionService;

    /**
     * Returns a validated group-level page of visible identity collisions.
     * @param query report filters and pagination
     * @return collision report page
     */
    @GetMapping
    @RequirePermission(Permission.REPORT_READ)
    public PageResponse<IdentityCollisionDto> list(
            @Valid @ModelAttribute IdentityCollisionQuery query) {
        return identityCollisionService.list(query);
    }
}
