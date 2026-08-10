package ooo.klae.connex.backend.mappers;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.dto.ClientErrorSupportRowDto;

/**
 * Persists and reads the control-plane, metadata-only client-error projection.
 */
public interface ClientErrorMapper {
    int insert(
        @Param("workspaceId") int workspaceId,
        @Param("correlationId") String correlationId,
        @Param("digest") String digest,
        @Param("pagePath") String pagePath);

    List<ClientErrorSupportRowDto> findOrgSupportSlice(
        @Param("orgId") int orgId,
        @Param("since") Instant since,
        @Param("until") Instant until,
        @Param("correlationId") String correlationId,
        @Param("limit") int limit);

    int deleteExpired();
}
