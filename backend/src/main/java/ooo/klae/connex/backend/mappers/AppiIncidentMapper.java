package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AppiIncident;
import ooo.klae.connex.backend.dto.AppiIncidentScopeDto;

public interface AppiIncidentMapper {
    int insert(AppiIncident incident);

    int update(AppiIncident incident);

    AppiIncident findById(@Param("orgId") int orgId, @Param("incidentId") long incidentId);

    List<AppiIncident> findByOrg(@Param("orgId") int orgId,
        @Param("limit") int limit,
        @Param("offset") int offset);

    List<AppiIncidentScopeDto> scopeFromAudit(@Param("orgId") int orgId,
        @Param("occurredFrom") LocalDateTime occurredFrom,
        @Param("occurredTo") LocalDateTime occurredTo,
        @Param("limit") int limit);
}
