package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.ProviderConnection;

/**
 * Mapper for {@code provider_connection}. Connections are user-owned control-plane rows;
 * every statement is scoped to the owning user id, which callers must resolve from the
 * authenticated session — never from request input.
 */
public interface ProviderConnectionMapper {
    List<ProviderConnection> getByUserId(@Param("userId") int userId);
    ProviderConnection getByUserAndProvider(@Param("userId") int userId, @Param("provider") String provider);
    ProviderConnection getByUserAndProviderForUpdate(
        @Param("userId") int userId, @Param("provider") String provider);
    ProviderConnection getByUserAndProviderForShare(
        @Param("userId") int userId, @Param("provider") String provider);
    ProviderConnection getById(@Param("id") int id);
    ProviderConnection getByIdForUpdate(@Param("id") int id);
    ProviderConnection getByIdForShare(@Param("id") int id);
    List<ProviderConnection> findDisconnecting(@Param("limit") int limit);
    List<ProviderConnection> findCaptureReconcileDue(@Param("limit") int limit);
    int insert(ProviderConnection connection);
    int update(ProviderConnection connection);
    int beginRevocation(@Param("userId") int userId, @Param("provider") String provider);
    int claimRevocationAttempt(@Param("id") int id, @Param("generation") long generation);
    int completeRevocation(@Param("id") int id, @Param("generation") long generation);
    int beginDisconnect(@Param("userId") int userId, @Param("provider") String provider);
    int claimDisconnectAttempt(@Param("id") int id, @Param("generation") long generation);
    boolean isDisconnectGeneration(
        @Param("id") int id, @Param("generation") long generation);
    int markPurgeFailed(@Param("id") int id, @Param("generation") long generation,
        @Param("owner") String owner, @Param("errorCode") String errorCode);
    int claimRefreshLease(@Param("id") int id, @Param("generation") long generation,
        @Param("owner") String owner, @Param("now") String now, @Param("until") String until);
    int completeRefresh(@Param("id") int id, @Param("generation") long generation,
        @Param("owner") String owner, @Param("credentialRef") String credentialRef,
        @Param("expiresAt") String expiresAt);
    int failRefresh(@Param("id") int id, @Param("generation") long generation,
        @Param("owner") String owner, @Param("errorCode") String errorCode);
    int releaseRefreshLease(
        @Param("id") int id,
        @Param("generation") long generation,
        @Param("owner") String owner,
        @Param("errorCode") String errorCode);
    int claimCaptureReconcile(
        @Param("id") int id,
        @Param("generation") long generation,
        @Param("owner") String owner,
        @Param("now") String now,
        @Param("until") String until);
    int renewCaptureReconcile(
        @Param("id") int id,
        @Param("generation") long generation,
        @Param("owner") String owner,
        @Param("until") String until);
    int advanceCaptureReconcile(
        @Param("id") int id,
        @Param("generation") long generation,
        @Param("owner") String owner,
        @Param("afterWorkspaceId") int afterWorkspaceId,
        @Param("complete") boolean complete);
    int releaseCaptureReconcile(
        @Param("id") int id,
        @Param("generation") long generation,
        @Param("owner") String owner);
    int deleteGeneration(@Param("id") int id, @Param("generation") long generation);
    int delete(@Param("userId") int userId, @Param("provider") String provider);
}
