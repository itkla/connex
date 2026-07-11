package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;

class TenantRoutingConfigTest {

    private TenantRoutingProperties routingProperties(String defaultCatalog) {
        TenantRoutingProperties properties = new TenantRoutingProperties();
        properties.setMode(TenantRoutingProperties.MODE_CATALOG_PER_PLACEMENT);
        properties.setDefaultCatalog(defaultCatalog);
        return properties;
    }

    @Test
    void armsPoolCatalogWhenUnset() {
        try (HikariDataSource hikari = new HikariDataSource()) {
            assertNotNull(TenantRoutingConfig.decorate(hikari, routingProperties("connexdb"), new TenantContext()));
            assertEquals("connexdb", hikari.getCatalog());
        }
    }

    @Test
    void acceptsMatchingExistingPoolCatalog() {
        try (HikariDataSource hikari = new HikariDataSource()) {
            hikari.setCatalog("connexdb");
            assertNotNull(TenantRoutingConfig.decorate(hikari, routingProperties("connexdb"), new TenantContext()));
        }
    }

    @Test
    void rejectsConflictingExistingPoolCatalog() {
        try (HikariDataSource hikari = new HikariDataSource()) {
            hikari.setCatalog("someothercatalog");
            assertThrows(IllegalStateException.class,
                () -> TenantRoutingConfig.decorate(hikari, routingProperties("connexdb"), new TenantContext()));
        }
    }

    @Test
    void rejectsBlankDefaultCatalog() {
        try (HikariDataSource hikari = new HikariDataSource()) {
            assertThrows(IllegalStateException.class,
                () -> TenantRoutingConfig.decorate(hikari, routingProperties("  "), new TenantContext()));
        }
    }
}
