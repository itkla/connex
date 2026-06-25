package ooo.klae.connex.backend.tenant;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that the annotated method requires {@link #value()} in the active workspace.
 *
 * <p>Enforced declaratively by {@link RequirePermissionAuthorizationManager} through Spring
 * Security method security (see {@code MethodSecurityConfig}). The manager delegates to
 * {@code WorkspaceService.requirePermission(Permission)}, which reads the active workspace from
 * {@link TenantContext} and the caller from the {@code SecurityContext} and throws
 * {@code ForbiddenException} (mapped to HTTP 403) when the permission is absent.
 *
 * <p>Enforcement is AOP-proxy based, so it fires on cross-bean calls (e.g. controller -&gt;
 * service) but NOT on self-invocation within the same bean. Place it on public entry points.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {
    Permission value();
}
