package ooo.klae.connex.backend.config;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

import ooo.klae.connex.backend.tenant.RequirePermission;
import ooo.klae.connex.backend.tenant.RequirePermissionAuthorizationManager;

/**
 * Enables Spring Security method security and registers the declarative {@link RequirePermission}
 * gate as a method interceptor. This is the enforcement backstop: an annotated method's check is
 * run by the framework on every (cross-bean) invocation, rather than relying on an author to call
 * {@code requirePermission(...)} by hand inside the body.
 *
 * <p>The interceptor runs at {@code PRE_AUTHORIZE} order, i.e. before the {@code @Transactional}
 * advice, so an unauthorized call never opens a transaction.
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor requirePermissionAuthorizationAdvisor(RequirePermissionAuthorizationManager manager) {
        AnnotationMatchingPointcut pointcut = new AnnotationMatchingPointcut(null, RequirePermission.class, true);
        AuthorizationManager<MethodInvocation> authorizationManager = manager;
        AuthorizationManagerBeforeMethodInterceptor interceptor =
            new AuthorizationManagerBeforeMethodInterceptor(pointcut, authorizationManager);
        interceptor.setOrder(AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder());
        return interceptor;
    }
}
