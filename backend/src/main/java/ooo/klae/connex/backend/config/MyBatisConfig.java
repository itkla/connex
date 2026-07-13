package ooo.klae.connex.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;
import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;

/**
 * Registers MyBatis plugins. {@code mybatis-spring-boot-starter} adds any
 * {@link org.apache.ibatis.plugin.Interceptor} bean to the SqlSessionFactory, so
 * declaring the tenant-scope interceptor here wires it as a fail-closed backstop
 * for workspace isolation.
 */
@Configuration
public class MyBatisConfig {

    /** The tenant-scope backstop; disable with {@code connex.tenancy.enforce-scope=false}. */
    @Bean
    TenantScopeInterceptor tenantScopeInterceptor(TenantContext tenantContext,
            @Value("${connex.tenancy.enforce-scope:true}") boolean enforce,
            @Value("${connex.tenancy.routing.mode:single-database}") String routingMode) {
        return new TenantScopeInterceptor(tenantContext, enforce,
            TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT.equals(routingMode));
    }
}
