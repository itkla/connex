package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.dto.ShareDto;

/**
 * Persistence for cross-workspace record shares (company / person / pipeline).
 * Every statement anchors on the owning workspace in SQL, and share grants
 * additionally enforce the same-organization ceiling structurally: a grant whose
 * record is not owned by {@code workspaceId} or whose target workspace belongs to
 * a different organization inserts nothing. ShareService remains the layer that
 * turns those refusals into errors and checks target membership.
 */
public interface ShareMapper {
    boolean ownsCompany(@Param("workspaceId") int workspaceId, @Param("id") int id);
    boolean ownsPerson(@Param("workspaceId") int workspaceId, @Param("id") int id);
    boolean ownsPipeline(@Param("workspaceId") int workspaceId, @Param("id") int id);

    int shareCompany(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId,
            @Param("grantedBy") int grantedBy, @Param("canEdit") boolean canEdit);
    int sharePerson(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId,
            @Param("grantedBy") int grantedBy, @Param("canEdit") boolean canEdit);
    int sharePipeline(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId,
            @Param("grantedBy") int grantedBy, @Param("canEdit") boolean canEdit);

    int unshareCompany(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId);
    int unsharePerson(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId);
    int unsharePipeline(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId);

    List<ShareDto> listCompanyShares(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<ShareDto> listPersonShares(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<ShareDto> listPipelineShares(@Param("workspaceId") int workspaceId, @Param("id") int id);
}
