package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.delivery.ConnectorConfigService;
import ooo.klae.connex.backend.dto.ConnectorConfigDto;
import ooo.klae.connex.backend.dto.ConnectorConfigRequest;

/**
 * Owner/admin third-party connector settings for the active workspace. The permission
 * ({@code WORKSPACE_SETTINGS}) and workspace scope are enforced in the service; push credentials are
 * write-only and never returned.
 */
@RestController
@RequestMapping("/api/delivery/connectors")
@RequiredArgsConstructor
@Validated
public class ConnectorConfigController {

    private final ConnectorConfigService connectorConfigService;

    @GetMapping
    public List<ConnectorConfigDto> list() {
        return connectorConfigService.list();
    }

    @PutMapping
    public ConnectorConfigDto save(@Valid @RequestBody ConnectorConfigRequest request) {
        return connectorConfigService.save(request);
    }

    @DeleteMapping("/{connector}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@Pattern(regexp = "[a-z_]{1,32}") @PathVariable String connector) {
        connectorConfigService.delete(connector);
    }
}
