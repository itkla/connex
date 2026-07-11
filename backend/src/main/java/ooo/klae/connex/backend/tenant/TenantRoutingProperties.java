package ooo.klae.connex.backend.tenant;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import lombok.Data;

/**
 * Placement-based catalog routing flags (#313 Phase 3 / #440 increment 2).
 * {@code single-database} (the default, and the only mode for on-prem/silo
 * deployments) never switches catalogs and fails closed when an organization's
 * placement demands routing. {@code catalog-per-placement} switches the pooled
 * connection to the org's {@code database_handle} at checkout and resets it on
 * return; it must not be enabled in production until the control-plane split
 * increment has landed.
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "connex.tenancy.routing")
public class TenantRoutingProperties {

    public static final String MODE_SINGLE_DATABASE = "single-database";
    public static final String MODE_CATALOG_PER_PLACEMENT = "catalog-per-placement";

    @NotBlank(message = "must not be blank")
    @Pattern(regexp = "^(single-database|catalog-per-placement)$",
        message = "must be single-database or catalog-per-placement")
    private String mode = MODE_SINGLE_DATABASE;

    private String defaultCatalog = "";

    private Duration placementCacheTtl = Duration.ofSeconds(30);

    public boolean isCatalogPerPlacement() {
        return MODE_CATALOG_PER_PLACEMENT.equals(mode);
    }
}
