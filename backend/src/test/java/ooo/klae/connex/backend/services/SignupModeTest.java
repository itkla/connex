package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.RegisterDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;

/**
 * The on-prem signup-mode seam: when {@code connex.signup.mode} is not {@code open}, anonymous
 * self-service registration ({@code registerSelfService}) is refused, while the permission-gated
 * admin create path ({@code register}) stays available so admins can still onboard teammates. The
 * default {@code open} path is covered by {@code AuthServiceTest} (#81 Phase 4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = "connex.signup.mode=invite")
@Transactional
class SignupModeTest {

    @Autowired private AuthService authService;

    @Test
    void selfServiceRegister_refusedWhenSignupModeNotOpen() {
        assertThrows(ForbiddenException.class,
            () -> authService.registerSelfService(dto("nope_user", "nope@example.com"), "127.0.0.1"));
    }

    @Test
    void adminCreatePath_isExemptWhenSignupModeNotOpen() {
        User created = authService.register(dto("admin_made", "admin.made@example.com"), true);

        assertNotNull(created.getId());
        assertTrue(created.isEmailVerified(), "admin-created accounts are trusted and start verified");
    }

    private static RegisterDto dto(String username, String email) {
        RegisterDto request = new RegisterDto();
        request.setUsername(username);
        request.setEmail(email);
        request.setDisplayName("T " + username);
        request.setPassword("Aa1!aaaa");
        request.setTimezone("UTC");
        return request;
    }
}
