package ooo.klae.connex.backend.tenant;

import java.lang.reflect.Method;
import java.util.Map;

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
 * Exact, reviewed handlers that can run without a tenant catalog. A server-owned capability
 * restricts SQL on the request thread independently of tenant identity. Descendants receive no
 * resolved tenant scope: ordinary scope enforcement requires their own placement-validated
 * TenantWorkScope or runAs span. Catalog routing requires that enforcement at startup.
 */
public final class ControlOnlyHandlerPolicy {
    private static final String ATTRIBUTE = ControlOnlyHandlerPolicy.class.getName() + ".CAPABILITY";
    private record RequestCapability(Thread owner) {}
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
        request.setAttribute(ATTRIBUTE, new RequestCapability(Thread.currentThread()));
    }

    /** Whether the current request thread may execute only physically control-plane statements. */
    static boolean isActive() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && attributes.getRequest().getAttribute(ATTRIBUTE) instanceof RequestCapability capability) {
            return capability.owner() == Thread.currentThread();
        }
        return false;
    }

    /** Removes the capability when a request dispatch starts or finishes. */
    static void clear(HttpServletRequest request) {
        request.removeAttribute(ATTRIBUTE);
    }

    private static Method method(Class<?> controller, String name, Class<?>... parameters) {
        try {
            return controller.getMethod(name, parameters);
        } catch (NoSuchMethodException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
