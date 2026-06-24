package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.dto.ShareDto;

/**
 * Persistence for cross-workspace record shares (company / person / pipeline).
 * Control-plane: ownership and target membership are checked in the service.
 */
public interface ShareMapper {
    boolean ownsCompany(@Param("workspaceId") int workspaceId, @Param("id") int id);
    boolean ownsPerson(@Param("workspaceId") int workspaceId, @Param("id") int id);
    boolean ownsPipeline(@Param("workspaceId") int workspaceId, @Param("id") int id);

    int shareCompany(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("grantedBy") int grantedBy, @Param("canEdit") boolean canEdit);
    int sharePerson(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("grantedBy") int grantedBy, @Param("canEdit") boolean canEdit);
    int sharePipeline(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("grantedBy") int grantedBy, @Param("canEdit") boolean canEdit);

    int unshareCompany(@Param("id") int id, @Param("workspaceId") int workspaceId);
    int unsharePerson(@Param("id") int id, @Param("workspaceId") int workspaceId);
    int unsharePipeline(@Param("id") int id, @Param("workspaceId") int workspaceId);

    List<ShareDto> listCompanyShares(int id);
    List<ShareDto> listPersonShares(int id);
    List<ShareDto> listPipelineShares(int id);
}
