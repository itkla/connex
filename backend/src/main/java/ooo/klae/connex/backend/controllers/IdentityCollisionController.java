package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.IdentityCollisionDto;
import ooo.klae.connex.backend.dto.IdentityCollisionMemberPageDto;
import ooo.klae.connex.backend.dto.IdentityCollisionMemberQuery;
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

    /**
     * Returns one keyset page from a JSON query body so canonical identity values stay out of
     * request targets. The read-only operation uses a fresh current-visibility repeatable-read
     * snapshot. A continuation request rechecks tenant, permission, and processing restrictions
     * and never retains or replays members that are no longer visible. Continuation is weakly
     * consistent across requests and may skip or repeat rows affected by concurrent identity or
     * restriction changes.
     * @param query group identity and member cursor
     * @return collision member page
     */
    @PostMapping("/members/query")
    @RequirePermission(Permission.REPORT_READ)
    public IdentityCollisionMemberPageDto listMembers(
            @Valid @RequestBody IdentityCollisionMemberQuery query) {
        return identityCollisionService.listMembers(query);
    }
}
