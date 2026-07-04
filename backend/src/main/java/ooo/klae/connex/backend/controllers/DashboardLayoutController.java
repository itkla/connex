package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.beans.UserDashboard;
import ooo.klae.connex.backend.dto.DashboardLayoutDto;
import ooo.klae.connex.backend.services.DashboardLayoutService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST controller for the current user's per-workspace dashboard layout. No
 * {@code @RequirePermission}: a member manages only their own layout, enforced by workspace + user
 * scoping in the service and mapper.
 */
@RestController
@RequestMapping("/api/dashboard-layout")
@RequiredArgsConstructor
public class DashboardLayoutController {
    private final DashboardLayoutService layoutService;

    /**
     * GET the current user's layout for the active workspace ({@code layout} is null if unset).
     */
    @GetMapping
    public DashboardLayoutDto get() {
        return toDto(layoutService.getLayout());
    }

    /**
     * PUT replaces the current user's layout for the active workspace.
     */
    @PutMapping
    public DashboardLayoutDto save(@Valid @RequestBody DashboardLayoutDto dto) {
        return toDto(layoutService.saveLayout(dto.getLayout()));
    }

    /**
     * DELETE resets the current user's dashboard to the default layout.
     */
    @DeleteMapping
    public void reset() {
        layoutService.resetLayout();
    }

    private DashboardLayoutDto toDto(UserDashboard dashboard) {
        if (dashboard == null) {
            return DashboardLayoutDto.empty();
        }
        DashboardLayoutDto dto = DashboardLayoutDto.from(dashboard);
        dto.setLayout(layoutService.parseLayout(dashboard.getLayoutJson()));
        return dto;
    }
}
