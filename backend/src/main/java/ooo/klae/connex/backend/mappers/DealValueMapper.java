package ooo.klae.connex.backend.mappers;

import java.math.BigDecimal;

import org.apache.ibatis.annotations.Param;

/** Persists the prospective manual-value fallback under the existing parent deal lock. */
public interface DealValueMapper {
    BigDecimal getPriorManualValue(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);

    BigDecimal getPriorManualValueForUpdate(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);

    int setPriorManualValue(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId,
        @Param("value") BigDecimal value);
}
