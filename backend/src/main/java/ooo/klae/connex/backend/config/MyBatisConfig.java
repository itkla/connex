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
}
