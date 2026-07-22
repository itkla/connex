package ooo.klae.connex.backend.controllers;

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
import ooo.klae.connex.backend.dto.ReportScheduleDto;
import ooo.klae.connex.backend.dto.ReportScheduleRequest;
import ooo.klae.connex.backend.services.ScheduleService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Workspace-scoped scheduled delivery for saved reports. */
@RestController
@RequestMapping("/api/reports/{reportId}/schedule")
@RequiredArgsConstructor
public class ScheduleController {
    private final ScheduleService scheduleService;

    @GetMapping
    @RequirePermission(Permission.REPORT_READ)
    public ReportScheduleDto get(@PathVariable int reportId) {
        return scheduleService.get(reportId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.REPORT_UPDATE)
    public ReportScheduleDto create(
            @PathVariable int reportId,
            @Valid @RequestBody ReportScheduleRequest request) {
        return scheduleService.create(reportId, request);
    }

    @PutMapping
    @RequirePermission(Permission.REPORT_UPDATE)
    public ReportScheduleDto update(
            @PathVariable int reportId,
            @Valid @RequestBody ReportScheduleRequest request) {
        return scheduleService.update(reportId, request);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(Permission.REPORT_UPDATE)
    public void delete(@PathVariable int reportId) {
        scheduleService.delete(reportId);
    }
}
