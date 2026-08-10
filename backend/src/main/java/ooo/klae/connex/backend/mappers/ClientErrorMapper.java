package ooo.klae.connex.backend.mappers;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.ClientErrorMetadataRow;

/**
 * Persists and reads the control-plane, metadata-only client-error projection.
 */
public interface ClientErrorMapper {
    int insert(
        @Param("workspaceId") int workspaceId,
        @Param("untrustedClientAssertedCorrelationHmac")
            String untrustedClientAssertedCorrelationHmac,
        @Param("pagePath") String pagePath);

    List<ClientErrorMetadataRow> findOrgSupportSlice(
        @Param("orgId") int orgId,
        @Param("since") Instant since,
        @Param("until") Instant until,
        @Param("untrustedClientAssertedCorrelationHmac")
            String untrustedClientAssertedCorrelationHmac,
        @Param("legacyRawCorrelationId") String legacyRawCorrelationId,
        @Param("limit") int limit);

    List<ClientErrorMetadataRow> findWorkspaceExportPage(
        @Param("workspaceId") int workspaceId,
        @Param("afterId") long afterId,
        @Param("limit") int limit);

    int deleteExpired();
}
