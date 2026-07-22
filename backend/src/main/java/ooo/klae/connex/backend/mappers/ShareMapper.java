package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.ShareDto;

/**
 * Persistence for cross-workspace record shares (company / person / pipeline).
 * Every statement anchors on the owning workspace in SQL. Share grants also
 * require the target to occur in the trusted organization workspace snapshot
 * supplied by the cross-plane service boundary. Because upsert row counts are
 * driver-dependent, the {@code *ShareExists} probes distinguish an idempotent
 * re-grant from a refused grant.
 */
public interface ShareMapper {
    boolean ownsCompany(@Param("workspaceId") int workspaceId, @Param("id") int id);
    boolean ownsPerson(@Param("workspaceId") int workspaceId, @Param("id") int id);
    boolean ownsPipeline(@Param("workspaceId") int workspaceId, @Param("id") int id);
    Person getOwnedPersonProvisionState(
        @Param("workspaceId") int workspaceId, @Param("id") int id);

    int shareCompany(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId,
            @Param("grantedBy") int grantedBy, @Param("canEdit") boolean canEdit,
            @Param("workspaceIds") List<Integer> workspaceIds);
    int sharePerson(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId,
            @Param("grantedBy") int grantedBy, @Param("canEdit") boolean canEdit,
            @Param("workspaceIds") List<Integer> workspaceIds);
    int sharePipeline(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId,
            @Param("grantedBy") int grantedBy, @Param("canEdit") boolean canEdit,
            @Param("workspaceIds") List<Integer> workspaceIds);

    boolean companyShareExists(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId);
    boolean personShareExists(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId);
    boolean pipelineShareExists(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId);

    int unshareCompany(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId);
    int unsharePerson(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId);
    int revokePersonShares(@Param("id") int id, @Param("workspaceId") int workspaceId);
    int unsharePipeline(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId);

    List<ShareDto> listCompanyShares(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<ShareDto> listPersonShares(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<ShareDto> listPipelineShares(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /**
     * Nulls the grantor reference on company shares granted by a user.
     * Offboarding replacement for the {@code company_share.granted_by}
     * ON DELETE SET NULL (#440 increment 3).
     */
    void clearCompanyShareGrantedByAnywhere(@Param("userId") int userId);

    /**
     * Nulls the grantor reference on person shares granted by a user.
     * Offboarding replacement for the {@code person_share.granted_by}
     * ON DELETE SET NULL (#440 increment 3).
     */
    void clearPersonShareGrantedByAnywhere(@Param("userId") int userId);

    /**
     * Nulls the grantor reference on pipeline shares granted by a user.
     * Offboarding replacement for the {@code pipeline_share.granted_by}
     * ON DELETE SET NULL (#440 increment 3).
     */
    void clearPipelineShareGrantedByAnywhere(@Param("userId") int userId);
}
