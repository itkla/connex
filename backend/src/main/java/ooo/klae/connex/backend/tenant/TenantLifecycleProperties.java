package ooo.klae.connex.backend.tenant;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import lombok.Data;

/** Bounded operational settings for tenant export and teardown. */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "connex.tenant-lifecycle")
public class TenantLifecycleProperties {

    @NotNull
    private Duration exportTimeout = Duration.ofMinutes(30);

    @NotNull
    private Duration exportObjectReadTimeout = Duration.ofMinutes(5);

    @NotNull
    private Duration teardownSettleDelay = Duration.ofSeconds(30);

    @Min(1)
    @Max(10_000)
    private int tableBatchSize = 500;

    @Min(1)
    @Max(10_000)
    private int objectPageSize = 100;

    @Min(1)
    @Max(10_000)
    private int workspacePageSize = 100;

    /** Whether all duration settings are positive or zero where waiting is optional. */
    @AssertTrue(message = "tenant lifecycle durations are invalid")
    public boolean durationsValid() {
        return positive(exportTimeout)
            && positive(exportObjectReadTimeout)
            && nonNegative(teardownSettleDelay);
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    private static boolean nonNegative(Duration duration) {
        return duration != null && !duration.isNegative();
    }
}
