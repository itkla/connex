package ooo.klae.connex.backend.tenant;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;

import ooo.klae.connex.backend.controllers.UserController;
import ooo.klae.connex.backend.controllers.WorkspaceController;
import ooo.klae.connex.backend.dto.UpdateLocaleDto;
import ooo.klae.connex.backend.dto.UserDto;

/**
 * Exact, reviewed handlers that can run without a tenant catalog. Request and descendant
 * restrictions are independent of tenant identity. Only a descendant's placement-validated
 * workspace span can admit tenant SQL; the originating HTTP handler remains control-only.
 */
public final class ControlOnlyHandlerPolicy {
    private static final String ATTRIBUTE = ControlOnlyHandlerPolicy.class.getName() + ".CAPABILITY";
    private record RequestCapability(Thread owner, Boolean previousOrigin) {}

    private static final InheritableThreadLocal<Boolean> CONTROL_ORIGIN = new InheritableThreadLocal<>();
    private static final ThreadLocal<WorkspacePermission> INDEPENDENT_WORKSPACE = new ThreadLocal<>();
    private static final Map<Method, String> HANDLERS = Map.of(
        method(WorkspaceController.class, "myWorkspaces"), "GET",
        method(WorkspaceController.class, "pending"), "GET",
        method(WorkspaceController.class, "switchWorkspace", int.class, HttpServletResponse.class), "POST",
        method(UserController.class, "getUserById", int.class), "GET",
        method(UserController.class, "updateUser", int.class, UserDto.class), "PUT",
        method(UserController.class, "updateCurrentUserLocale", UpdateLocaleDto.class), "PATCH");

    private ControlOnlyHandlerPolicy() {}

    /** Returns the immutable handler and HTTP-method contract inspected by architecture tests. */
    public static Map<Method, String> handlers() {
        return HANDLERS;
    }

    /** Matches a concrete mapped handler, including implicit HEAD for an approved GET. */
    static boolean allows(String requestMethod, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)
                || handlerMethod.getBeanType() != handlerMethod.getMethod().getDeclaringClass()) {
            return false;
        }
        String method = HANDLERS.get(handlerMethod.getMethod());
        return method != null && (method.equals(requestMethod)
            || ("GET".equals(method) && "HEAD".equals(requestMethod)));
    }

    /** Restricts this request to physically control-plane mapper statements. */
    static void activate(HttpServletRequest request) {
        request.setAttribute(ATTRIBUTE, new RequestCapability(Thread.currentThread(), CONTROL_ORIGIN.get()));
        CONTROL_ORIGIN.set(true);
    }

    /** Whether this request or descendant may execute only physically control-plane statements. */
    static boolean isActive() {
        RequestCapability capability = requestCapability();
        WorkspacePermission workspace = INDEPENDENT_WORKSPACE.get();
        return (capability != null && capability.owner() == Thread.currentThread())
            || ((capability != null || Boolean.TRUE.equals(CONTROL_ORIGIN.get()))
                && (workspace == null || !workspace.isPinned()));
    }

    /**
     * Captures only the SQL restriction, never a caller's tenant identity or catalog. Spring async
     * executors use this for pooled threads; newly created child threads inherit the origin too.
     * Every descendant must resolve its own workspace before accessing tenant data.
     */
    public static Runnable decorate(Runnable task) {
        boolean restricted = requestCapability() != null || Boolean.TRUE.equals(CONTROL_ORIGIN.get());
        return () -> {
            Boolean previousOrigin = CONTROL_ORIGIN.get();
            WorkspacePermission previousWorkspace = INDEPENDENT_WORKSPACE.get();
            restore(CONTROL_ORIGIN, restricted ? Boolean.TRUE : null);
            INDEPENDENT_WORKSPACE.remove();
            try {
                task.run();
            } finally {
                restore(INDEPENDENT_WORKSPACE, previousWorkspace);
                restore(CONTROL_ORIGIN, previousOrigin);
            }
        };
    }

    /** A permission span created only after TenantWorkScope has validated and pinned placement. */
    static final class WorkspacePermission implements AutoCloseable {
        private final WorkspacePermission previous = INDEPENDENT_WORKSPACE.get();
        private final TenantContext context;
        private final String catalog;

        private WorkspacePermission(TenantContext context, String catalog) {
            this.context = context;
            this.catalog = catalog;
            INDEPENDENT_WORKSPACE.set(this);
        }

        private boolean isPinned() {
            return context.hasCatalogOverride() && Objects.equals(context.getCatalog(), catalog);
        }

        @Override
        public void close() {
            restore(INDEPENDENT_WORKSPACE, previous);
        }
    }

    /** Opens a descendant's independently resolved workspace span; it never exempts the HTTP owner. */
    static WorkspacePermission independentlyResolvedWorkspace(TenantContext context, String catalog) {
        return new WorkspacePermission(context, catalog);
    }

    /** Removes the capability when a request dispatch starts or finishes. */
    static void clear(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof RequestCapability capability
                && capability.owner() == Thread.currentThread()) {
            restore(CONTROL_ORIGIN, capability.previousOrigin());
        }
        request.removeAttribute(ATTRIBUTE);
    }

    private static RequestCapability requestCapability() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && attributes.getRequest().getAttribute(ATTRIBUTE) instanceof RequestCapability capability) {
            return capability;
        }
        return null;
    }

    private static <T> void restore(ThreadLocal<T> local, T value) {
        if (value == null) {
            local.remove();
        } else {
            local.set(value);
        }
    }

    private static Method method(Class<?> controller, String name, Class<?>... parameters) {
        try {
            return controller.getMethod(name, parameters);
        } catch (NoSuchMethodException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
