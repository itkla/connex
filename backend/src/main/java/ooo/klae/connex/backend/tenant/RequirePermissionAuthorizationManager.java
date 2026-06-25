package ooo.klae.connex.backend.tenant;

import java.lang.reflect.Method;
import java.util.function.Supplier;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * Method-security {@link AuthorizationManager} backing the {@link RequirePermission} annotation.
 *
 * <p>It does not re-implement the permission model: it delegates to the existing tenant-scoped
 * {@code WorkspaceService.requirePermission(Permission)}, which throws {@code ForbiddenException}
 * (-&gt; HTTP 403) when the caller lacks the permission in the active workspace. Throwing from
 * {@link #authorize} propagates the original exception unchanged (no SpEL wrapping), preserving
 * the existing 403 contract and error messages. On success it returns a granted decision.
 *
 * <p>{@code WorkspaceService} is resolved lazily via {@link ObjectProvider} so wiring this
 * infrastructure advisor never forces early initialization of the service graph.
 */
@Component
public class RequirePermissionAuthorizationManager implements AuthorizationManager<MethodInvocation> {

    private final ObjectProvider<WorkspaceService> workspaceService;

    public RequirePermissionAuthorizationManager(ObjectProvider<WorkspaceService> workspaceService) {
        this.workspaceService = workspaceService;
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication, MethodInvocation invocation) {
        RequirePermission annotation = findAnnotation(invocation);
        if (annotation != null) {
            workspaceService.getObject().requirePermission(annotation.value());
        }
        return new AuthorizationDecision(true);
    }

    private static RequirePermission findAnnotation(MethodInvocation invocation) {
        Method method = invocation.getMethod();
        return AnnotatedElementUtils.findMergedAnnotation(method, RequirePermission.class);
    }
}
