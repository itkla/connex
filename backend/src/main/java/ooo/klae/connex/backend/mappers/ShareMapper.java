package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.dto.ShareDto;

/**
 * Persistence for cross-workspace record shares (company / person / pipeline).
 * Every statement anchors on the owning workspace in SQL, and share grants
 * additionally enforce the same-organization ceiling structurally: a grant whose
 * record is not owned by {@code workspaceId} or whose target workspace is outside
 * the owning organization inserts nothing and returns 0. Because upsert row
 * counts are driver-dependent, the {@code *ShareExists} probes let ShareService
 * distinguish an idempotent re-grant from a refusal before erroring; it also
 * checks target membership.
 *
 * <p>The organization ceiling reads {@code orgWorkspaceIdsJson} — the control-derived
 * workspace allowlist ShareService loads in one unrouted snapshot — rather than joining
 * the control-plane {@code workspace} table, which does not exist in a dedicated org
 * catalog (#811). Only ShareService may supply that allowlist; every statement here
 * references org-data tables only. Listings therefore return no workspace name: the name
 * and the name ordering are hydrated from the same snapshot.
 */
public interface ShareMapper {
    boolean ownsCompany(@Param("workspaceId") int workspaceId, @Param("id") int id);
    boolean ownsPerson(@Param("workspaceId") int workspaceId, @Param("id") int id);
    boolean ownsPipeline(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /**
     * Grants a company share, refusing anything outside the supplied organization allowlist.
     *
     * @param id company being shared
     * @param workspaceId workspace that must own the company
     * @param targetWorkspaceId workspace receiving visibility
     * @param grantedBy acting user recorded on the grant
     * @param canEdit whether the grantee may edit
     * @param orgWorkspaceIdsJson control-derived JSON array of the organization's workspace ids
     * @return rows affected; 0 when the ceiling refused the grant
     */
    int shareCompany(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId,
            @Param("grantedBy") int grantedBy, @Param("canEdit") boolean canEdit,
            @Param("orgWorkspaceIdsJson") String orgWorkspaceIdsJson);

    /**
     * Grants a contact share, refusing anything outside the supplied organization allowlist
     * and any contact whose third-party provision has ceased.
     *
     * @param id contact being shared
     * @param workspaceId workspace that must own the contact
     * @param targetWorkspaceId workspace receiving visibility
     * @param grantedBy acting user recorded on the grant
     * @param canEdit whether the grantee may edit
     * @param orgWorkspaceIdsJson control-derived JSON array of the organization's workspace ids
     * @return rows affected; 0 when the ceiling or the provision guard refused the grant
     */
    int sharePerson(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId,
            @Param("grantedBy") int grantedBy, @Param("canEdit") boolean canEdit,
            @Param("orgWorkspaceIdsJson") String orgWorkspaceIdsJson);

    /**
     * Grants a pipeline share, refusing anything outside the supplied organization allowlist.
     *
     * @param id pipeline being shared
     * @param workspaceId workspace that must own the pipeline
     * @param targetWorkspaceId workspace receiving visibility
     * @param grantedBy acting user recorded on the grant
     * @param canEdit whether the grantee may edit
     * @param orgWorkspaceIdsJson control-derived JSON array of the organization's workspace ids
     * @return rows affected; 0 when the ceiling refused the grant
     */
    int sharePipeline(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId,
            @Param("grantedBy") int grantedBy, @Param("canEdit") boolean canEdit,
            @Param("orgWorkspaceIdsJson") String orgWorkspaceIdsJson);

    boolean companyShareExists(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId);
    boolean personShareExists(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId);
    boolean pipelineShareExists(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId);

    Integer lockCompanyShareForWorkspace(
        @Param("id") int id,
        @Param("workspaceId") int workspaceId);
    Integer lockPersonShareForWorkspace(
        @Param("id") int id,
        @Param("workspaceId") int workspaceId);
    Integer lockPipelineShareForWorkspace(
        @Param("id") int id,
        @Param("workspaceId") int workspaceId);

    int unshareCompany(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId);
    int unsharePerson(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId);
    int revokePersonShares(@Param("id") int id, @Param("workspaceId") int workspaceId);
    int unsharePipeline(@Param("id") int id, @Param("workspaceId") int workspaceId,
            @Param("targetWorkspaceId") int targetWorkspaceId);

    /**
     * Lists the company's share rows without their workspace names.
     *
     * @param workspaceId workspace that must own the company
     * @param id company whose shares are listed
     * @return share rows with a null {@code workspaceName}, awaiting control-plane hydration
     */
    List<ShareDto> listCompanyShares(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /**
     * Lists the contact's share rows without their workspace names.
     *
     * @param workspaceId workspace that must own the contact
     * @param id contact whose shares are listed
     * @return share rows with a null {@code workspaceName}, awaiting control-plane hydration
     */
    List<ShareDto> listPersonShares(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /**
     * Lists the pipeline's share rows without their workspace names.
     *
     * @param workspaceId workspace that must own the pipeline
     * @param id pipeline whose shares are listed
     * @return share rows with a null {@code workspaceName}, awaiting control-plane hydration
     */
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
