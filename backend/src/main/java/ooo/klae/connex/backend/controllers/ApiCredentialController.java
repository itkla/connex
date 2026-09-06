package ooo.klae.connex.backend.controllers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.publicapi.ApiCredentialService;
import ooo.klae.connex.backend.publicapi.ApiCredentialService.CredentialView;
import ooo.klae.connex.backend.publicapi.ApiCredentialService.IssuedCredential;
import ooo.klae.connex.backend.publicapi.ApiScope;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Private browser-session management endpoints for workspace-bound API credentials. */
@RestController
@RequestMapping("/api/api-credentials")
@RequiredArgsConstructor
@Validated
public class ApiCredentialController {
    private final ApiCredentialService apiCredentialService;

    /** Lists secret-free credential metadata for the active workspace. */
    @GetMapping
    @RequirePermission(Permission.API_CREDENTIAL_MANAGE)
    public List<CredentialView> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return apiCredentialService.list(page, size);
    }

    /** Issues one credential and reveals its plaintext bearer once. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(Permission.API_CREDENTIAL_MANAGE)
    public IssuedCredential issue(@Valid @RequestBody IssueCredentialRequest request) {
        return apiCredentialService.issue(request.name(), request.scopes(), request.expiresAt());
    }

    /** Soft-revokes one credential in the active workspace. */
    @DeleteMapping("/{id:\\d+}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(Permission.API_CREDENTIAL_MANAGE)
    public void revoke(@PathVariable long id) {
        apiCredentialService.revoke(id);
    }

    /** Validated private request for a one-time credential issuance. */
    public record IssueCredentialRequest(
            @NotBlank @Size(max = 128) String name,
            @NotEmpty Set<@NotNull ApiScope> scopes,
            @NotNull @Future LocalDateTime expiresAt) {
    }
}
