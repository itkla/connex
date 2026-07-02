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
import ooo.klae.connex.backend.dto.MailConfigDto;
import ooo.klae.connex.backend.dto.MailConfigRequest;
import ooo.klae.connex.backend.dto.MailTestResult;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.WorkspaceMailConfigService;

/**
 * Owner/admin SMTP settings for a workspace: read, upsert, delete, and send a
 * test email. Permission ({@code WORKSPACE_SETTINGS}) is enforced in the service.
 */
@RestController
@RequestMapping("/api/workspaces/{id}/mail-config")
@RequiredArgsConstructor
public class WorkspaceMailConfigController {

    private final WorkspaceMailConfigService mailConfigService;
    private final AuthService authService;

    @GetMapping
    public MailConfigDto get(@PathVariable int id) {
        return mailConfigService.getConfig(id, authService.getCurrentUser().getId());
    }

    @PutMapping
    public MailConfigDto save(@PathVariable int id, @Valid @RequestBody MailConfigRequest request) {
        return mailConfigService.saveConfig(id, authService.getCurrentUser().getId(), request);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable int id) {
        mailConfigService.deleteConfig(id, authService.getCurrentUser().getId());
    }

    @PostMapping("/test")
    public MailTestResult test(@PathVariable int id) {
        return mailConfigService.sendTest(id, authService.getCurrentUser().getId());
    }
}
