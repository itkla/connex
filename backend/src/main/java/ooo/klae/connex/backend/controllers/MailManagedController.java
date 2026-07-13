package ooo.klae.connex.backend.controllers;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mail.MailProperties;

/**
 * Instance-level mail capability endpoints.
 */
@RestController
@RequestMapping("/api/mail")
@RequiredArgsConstructor
public class MailManagedController {

    private final MailProperties mailProperties;

    @GetMapping("/managed")
    public Map<String, Boolean> managed() {
        return Map.of("managed", mailProperties.isManaged());
    }
}
