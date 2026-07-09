package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.tenant.Permission;

/**
 * The fixed permission catalog, used by the role editor to render the togglable
 * permissions a custom role can grant. Inert permissions (SSO moved to org-level
 * authorization) are excluded so the editor never offers a grant the backend no
 * longer honors.
 */
@RestController
@RequestMapping("/api/permissions")
public class PermissionController {

    @GetMapping
    public List<String> catalog() {
        return Permission.grantableNames();
    }
}
