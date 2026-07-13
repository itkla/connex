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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.ReportGoalDto;
import ooo.klae.connex.backend.dto.ReportGoalRequest;
import ooo.klae.connex.backend.services.GoalService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Workspace-scoped report goal management endpoints. */
@RestController
@RequestMapping("/api/goals")
@RequiredArgsConstructor
public class GoalController {
    private final GoalService goalService;

    @GetMapping
    @RequirePermission(Permission.GOAL_READ)
    public List<ReportGoalDto> list() {
        return goalService.list();
    }

    @GetMapping("/{id}")
    @RequirePermission(Permission.GOAL_READ)
    public ReportGoalDto get(@PathVariable int id) {
        return goalService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.GOAL_MANAGE)
    public ReportGoalDto create(@Valid @RequestBody ReportGoalRequest request) {
        return goalService.create(request);
    }

    @PutMapping("/{id}")
    @RequirePermission(Permission.GOAL_MANAGE)
    public ReportGoalDto update(@PathVariable int id, @Valid @RequestBody ReportGoalRequest request) {
        return goalService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(Permission.GOAL_MANAGE)
    public void delete(@PathVariable int id) {
        goalService.delete(id);
    }
}
