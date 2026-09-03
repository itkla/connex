package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.TeamDto;
import ooo.klae.connex.backend.dto.TeamMemberRequest;
import ooo.klae.connex.backend.dto.TeamRequest;
import ooo.klae.connex.backend.services.TeamService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Authenticated active-workspace endpoints for teams and their seats. */
@RestController
@RequestMapping("/api/teams")
@RequiredArgsConstructor
public class TeamController {
    private final TeamService teamService;

    /** Lists active teams unless archived rows are explicitly requested. */
    @GetMapping
    public List<TeamDto> list(
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return teamService.list(includeArchived);
    }

    /** Returns one team in the active workspace. */
    @GetMapping("/{id:\\d+}")
    public TeamDto get(@PathVariable int id) {
        return teamService.get(id);
    }

    /** Creates a team. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.TEAM_MANAGE)
    public TeamDto create(@Valid @RequestBody TeamRequest request) {
        return teamService.create(request);
    }

    /** Replaces a team's editable fields. */
    @PutMapping("/{id:\\d+}")
    @RequirePermission(Permission.TEAM_MANAGE)
    public TeamDto update(@PathVariable int id, @Valid @RequestBody TeamRequest request) {
        return teamService.update(id, request);
    }

    /** Soft-archives a team. */
    @PostMapping("/{id:\\d+}/archive")
    @RequirePermission(Permission.TEAM_MANAGE)
    public TeamDto archive(@PathVariable int id) {
        return teamService.archive(id);
    }

    /** Adds a seat or replaces its role. */
    @PostMapping("/{id:\\d+}/members")
    @RequirePermission(Permission.TEAM_MANAGE)
    public TeamDto addMember(
            @PathVariable int id, @Valid @RequestBody TeamMemberRequest request) {
        return teamService.addMember(id, request);
    }

    /** Removes one seat. */
    @DeleteMapping("/{id:\\d+}/members/{userId:\\d+}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(Permission.TEAM_MANAGE)
    public void removeMember(
            @PathVariable int id, @PathVariable @Positive int userId) {
        teamService.removeMember(id, userId);
    }
}
