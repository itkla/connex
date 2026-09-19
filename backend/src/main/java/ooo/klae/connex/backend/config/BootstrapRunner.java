package ooo.klae.connex.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.RegisterDto;
import ooo.klae.connex.backend.exceptions.PasswordTooLongException;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.AuthService;

/**
 * Provisions the initial owner account and workspace from environment configuration when an
 * instance starts with zero login-capable users (a fresh self-host/on-prem deploy). Runs once —
 * it is a no-op as soon as any real user exists — and never aborts startup: misconfiguration is
 * logged and skipped rather than crashing the boot. The bootstrap password is read from the
 * environment and is never logged. Bean validation does not run on this path, so a password longer
 * than the credential encoder accepts is reported by the fixed {@link PasswordTooLongException}
 * message, which names the limit but never the password.
 */
@Component
@ConditionalOnProperty(
    prefix = "connex.maintenance",
    name = "mode",
    havingValue = "off",
    matchIfMissing = true)
@Order(BootstrapRunner.ORDER)
@RequiredArgsConstructor
public class BootstrapRunner implements ApplicationListener<ApplicationReadyEvent> {

    /**
     * Ready-listener order of owner provisioning. Ready listeners that must observe the founding
     * owner, such as the privileged-MFA posture audit, order after it; the lowest-precedence
     * readiness marker still runs last.
     */
    static final int ORDER = Ordered.LOWEST_PRECEDENCE - 2;

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final UserMapper userMapper;
    private final AuthService authService;

    @Value("${connex.bootstrap.enabled:false}")
    private boolean enabled;
    @Value("${connex.bootstrap.username:}")
    private String username;
    @Value("${connex.bootstrap.email:}")
    private String email;
    @Value("${connex.bootstrap.password:}")
    private String password;
    @Value("${connex.bootstrap.display-name:}")
    private String displayName;
    @Value("${connex.bootstrap.timezone:UTC}")
    private String timezone;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!enabled) {
            return;
        }
        try {
            if (userMapper.countUsers() > 0) {
                log.info("Bootstrap owner skipped: the instance already has login-capable users.");
                return;
            }
            if (isBlank(username) || isBlank(email) || isBlank(password)) {
                log.warn("Bootstrap is enabled but username, email, and password are not all set "
                        + "— skipping owner provisioning.");
                return;
            }
            RegisterDto request = new RegisterDto();
            request.setUsername(username.trim());
            request.setEmail(email.trim());
            request.setDisplayName(isBlank(displayName) ? username.trim() : displayName.trim());
            request.setPassword(password);
            request.setTimezone(timezone);
            User owner = authService.provisionBootstrapOwner(request);
            log.info("Bootstrap owner userId {} provisioned with an initial workspace.", owner.getId());
        } catch (PasswordTooLongException tooLong) {
            log.error("Bootstrap owner provisioning failed; the instance still has no owner. "
                    + "connex.bootstrap.password is too long: {} Fix it and restart.",
                    PasswordTooLongException.MESSAGE);
        } catch (Exception e) {
            log.error("Bootstrap owner provisioning failed; the instance still has no owner. "
                    + "Fix the connex.bootstrap.* configuration and restart. exception={}",
                    e.getClass().getName());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
