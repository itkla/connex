package ooo.klae.connex.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ooo.klae.connex.backend.tenant.ControlCatalogRoutingInterceptor;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;

/**
 * Registers MyBatis plugins. {@code mybatis-spring-boot-starter} adds any
 * {@link org.apache.ibatis.plugin.Interceptor} bean to the SqlSessionFactory, so
 * declaring the tenant-scope and catalog-routing interceptors here wires the
 * fail-closed workspace isolation and physical plane routing backstops.
 */
@Configuration
public class MyBatisConfig {

    /** Routes physically control-only mapper statements during dedicated transactions. */
    @Bean
    ControlCatalogRoutingInterceptor controlCatalogRoutingInterceptor(
            TenantContext tenantContext,
            @Value("${connex.tenancy.routing.mode:single-database}") String routingMode) {
        return new ControlCatalogRoutingInterceptor(
            tenantContext,
            TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT.equals(routingMode));
    }

    /** The tenant-scope backstop; disable with {@code connex.tenancy.enforce-scope=false}. */
    @Bean
    TenantScopeInterceptor tenantScopeInterceptor(TenantContext tenantContext,
            @Value("${connex.tenancy.enforce-scope:true}") boolean enforce,
            @Value("${connex.tenancy.routing.mode:single-database}") String routingMode) {
        return new TenantScopeInterceptor(tenantContext, enforce,
            TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT.equals(routingMode));
    }
    /** Hydrates notification source authority through independent control reads without plane joins. */
    @Bean
    ooo.klae.connex.backend.notifications.NotificationPrivacyInterceptor notificationPrivacyInterceptor(
            org.springframework.beans.factory.ObjectProvider<ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService> authority,
            org.springframework.beans.factory.ObjectProvider<ooo.klae.connex.backend.tenant.TenantWorkScope> workScope,
            org.springframework.beans.factory.ObjectProvider<org.springframework.transaction.PlatformTransactionManager> transactions) {
        return new ooo.klae.connex.backend.notifications.NotificationPrivacyInterceptor(
                (recipientId, workspaceId) -> {
                    var detached = new org.springframework.transaction.support.TransactionTemplate(transactions.getObject());
                    detached.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
                    return detached.execute(status -> workScope.getObject().unroutedRead(
                            () -> authority.getObject().notificationWorkspaceGenerations(recipientId, workspaceId)));
                });
    }

}
