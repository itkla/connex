package ooo.klae.connex.backend.ai;

import java.util.Locale;
import java.util.Objects;

import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.AutomationExecutor;
import ooo.klae.connex.backend.services.WorkspaceService;

/** Reloads and installs the initiating identity, tenant placement, and locale for AI work. */
@Component
@RequiredArgsConstructor
public class AiGenerationContextRunner {
    private final UserMapper userMapper;
    private final WorkspaceService workspaceService;
    private final AutomationExecutor automationExecutor;

    /** Runs one task under a freshly loaded principal and current workspace membership. */
    public void run(int workspaceId, int userId, Locale locale, Runnable work) {
        User principal = userMapper.getUserById(userId);
        String role = workspaceService.getRole(workspaceId, userId);
        if (principal == null || role == null) {
            throw new ResourceNotFoundException("AI generation identity is unavailable");
        }
        LocaleContext previous = LocaleContextHolder.getLocaleContext();
        LocaleContextHolder.setLocale(Objects.requireNonNull(locale, "locale"));
        try {
            automationExecutor.runAs(workspaceId, principal, role, () -> {
                work.run();
                return null;
            });
        } finally {
            if (previous == null) {
                LocaleContextHolder.resetLocaleContext();
            } else {
                LocaleContextHolder.setLocaleContext(previous);
            }
        }
    }
}
