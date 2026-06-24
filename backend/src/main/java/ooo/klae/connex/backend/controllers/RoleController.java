package ooo.klae.connex.backend.controllers;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.RoleRequest;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.RoleService;

/**
 * Custom role administration for a workspace, gated on the ROLE_MANAGE permission.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/roles")
@RequiredArgsConstructor
public class RoleController {
    private final RoleService roleService;
    private final AuthService authService;

    @GetMapping
    public List<WorkspaceRole> list(@PathVariable int workspaceId) {
        return roleService.listRoles(workspaceId, authService.getCurrentUser().getId());
    }

    @PostMapping
    public WorkspaceRole create(@PathVariable int workspaceId, @Valid @RequestBody RoleRequest request) {
        return roleService.createRole(workspaceId, authService.getCurrentUser().getId(),
                request.getName(), request.getPermissions());
    }

    @PutMapping("/{roleId}")
    public WorkspaceRole update(@PathVariable int workspaceId, @PathVariable int roleId,
            @Valid @RequestBody RoleRequest request) {
        return roleService.updateRole(workspaceId, authService.getCurrentUser().getId(), roleId,
                request.getName(), request.getPermissions());
    }

    @DeleteMapping("/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable int workspaceId, @PathVariable int roleId) {
        roleService.deleteRole(workspaceId, authService.getCurrentUser().getId(), roleId);
    }
}
