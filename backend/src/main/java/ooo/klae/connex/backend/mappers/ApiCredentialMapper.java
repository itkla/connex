package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.publicapi.ApiCredential;
import ooo.klae.connex.backend.publicapi.ApiCredentialReferenceRoot;
import ooo.klae.connex.backend.publicapi.ApiCredentialScope;

/**
 * Control-plane persistence for API credential resolution and management.
 * Token lookup is deliberately unscoped because it runs before tenant routing can be resolved;
 * every management statement carries an explicit workspace key.
 */
public interface ApiCredentialMapper {
    ApiCredential findByTokenHash(@Param("tokenHash") String tokenHash);

    ApiCredential findRoutingByTokenHash(@Param("tokenHash") String tokenHash);

    ApiCredential findByIdWithScopes(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id);

    List<String> findScopes(
        @Param("workspaceId") int workspaceId,
        @Param("credentialId") long credentialId);

    List<ApiCredential> listByWorkspace(
        @Param("workspaceId") int workspaceId,
        @Param("limit") int limit,
        @Param("offset") long offset);

    List<ApiCredentialScope> findScopesByCredentialIds(
        @Param("workspaceId") int workspaceId,
        @Param("credentialIds") List<Long> credentialIds);

    ApiCredential findByIdForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id);

    int insert(ApiCredential credential);

    int insertScopes(
        @Param("credentialId") long credentialId,
        @Param("scopes") List<String> scopes);

    int updateLastUsed(
        @Param("id") long id,
        @Param("tokenHash") String tokenHash);

    int revoke(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("revokedById") int revokedById);

    int countActiveByMembership(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("membershipId") long membershipId);

    int deleteInactiveByMembership(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId,
        @Param("membershipId") long membershipId);

    List<ApiCredential> listByMembershipForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId);

    List<ApiCredential> listByAccountReference(@Param("userId") int userId);

    List<ApiCredentialReferenceRoot> listAccountReferenceRoots(@Param("userId") int userId);

    int deleteByMembership(
        @Param("workspaceId") int workspaceId,
        @Param("userId") int userId);

    int deleteById(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id);
}
