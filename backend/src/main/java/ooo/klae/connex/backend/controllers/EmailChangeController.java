package ooo.klae.connex.backend.controllers;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.EmailChangeConfirmDto;
import ooo.klae.connex.backend.dto.EmailChangeRequestDto;
import ooo.klae.connex.backend.services.EmailChangeService;
import ooo.klae.connex.backend.util.ClientIpResolver;

/**
 * Verified account email-change endpoints. Initiation is authenticated and
 * CSRF-protected (it changes the caller's own account); validation and
 * confirmation live under {@code /api/auth/**} so a recipient can redeem the
 * emailed link without a prior session — the token from the new address is the
 * bearer credential.
 */
@RestController
@RequiredArgsConstructor
public class EmailChangeController {

    private final EmailChangeService emailChangeService;
    private final ClientIpResolver clientIpResolver;

    @PostMapping("/api/users/me/email-change")
    public Map<String, String> request(@Valid @RequestBody EmailChangeRequestDto dto,
            HttpServletRequest httpRequest) {
        emailChangeService.requestChange(dto.getNewEmail(), dto.getCurrentPassword(),
                clientIpResolver.resolve(httpRequest));
        return Map.of("message", "Check your new email address for a verification link");
    }

    @GetMapping("/api/auth/email-change/validate")
    public Map<String, Boolean> validate(@RequestParam("token") String token) {
        return Map.of("valid", emailChangeService.validateToken(token));
    }

    @PostMapping("/api/auth/email-change/confirm")
    public Map<String, String> confirm(@Valid @RequestBody EmailChangeConfirmDto dto) {
        emailChangeService.confirmChange(dto.getToken());
        return Map.of("message", "Your email address has been updated");
    }
}
