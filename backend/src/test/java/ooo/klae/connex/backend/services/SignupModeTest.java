package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.dto.RegisterDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;

/**
 * The on-prem signup-mode seam: when {@code connex.signup.mode} is not {@code open}, self-service
 * registration is refused (users onboard via invites instead). The default {@code open} path is
 * covered by {@code AuthServiceTest} (#81 Phase 4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = "connex.signup.mode=invite")
@Transactional
class SignupModeTest {

    @Autowired private AuthService authService;

    @Test
    void register_refusedWhenSignupModeNotOpen() {
        RegisterDto dto = new RegisterDto();
        dto.setUsername("nope_user");
        dto.setEmail("nope@example.com");
        dto.setDisplayName("Nope");
        dto.setPassword("Aa1!aaaa");
        dto.setTimezone("UTC");

        assertThrows(ForbiddenException.class, () -> authService.register(dto));
    }
}
