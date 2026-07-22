package ooo.klae.connex.backend.controllers;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.connectedaccounts.ProviderConnectionService;
import ooo.klae.connex.backend.dto.ProviderConnectionDto;

/**
 * REST controller for the current user's external provider connections. All endpoints are
 * self-scoped: the acting user comes from the authenticated session. The callback endpoint
 * issues a browser redirect back to the app; every other endpoint is JSON.
 */
@RestController
@RequestMapping("/api/account/connections")
@RequiredArgsConstructor
public class ProviderConnectionController {
    private final ProviderConnectionService connectionService;

    @GetMapping
    public List<ProviderConnectionDto> getConnections() {
        return connectionService.getForCurrentUser();
    }

    @PostMapping("/{provider}/authorize")
    public Map<String, String> authorize(@PathVariable String provider) {
        return Map.of("url", connectionService.beginAuthorization(provider));
    }

    @GetMapping("/callback/{provider}")
    public void callback(@PathVariable String provider,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false, name = "error") String providerError,
            HttpServletResponse response) throws IOException {
        response.sendRedirect(connectionService.completeCallback(provider, code, state, providerError));
    }

    @PostMapping("/{provider}/pause")
    public ProviderConnectionDto pause(@PathVariable String provider) {
        return connectionService.pause(provider);
    }

    @PostMapping("/{provider}/resume")
    public ProviderConnectionDto resume(@PathVariable String provider) {
        return connectionService.resume(provider);
    }

    @DeleteMapping("/{provider}")
    public void disconnect(@PathVariable String provider) {
        connectionService.disconnect(provider);
    }
}
