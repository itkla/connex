package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.NativeConnectSession;

/** Control-plane persistence for single-use managed native authorization sessions. */
public interface NativeConnectSessionMapper {
    NativeConnectSession getLatestByUserAndProvider(
        @Param("userId") int userId, @Param("provider") String provider);
    NativeConnectSession getLatestByUserAndProviderForUpdate(
        @Param("userId") int userId, @Param("provider") String provider);
    NativeConnectSession getByIdForUpdate(@Param("id") int id);
    NativeConnectSession getByPairingCodeHash(@Param("pairingCodeHash") byte[] pairingCodeHash);
    NativeConnectSession getByPairingCodeHashForUpdate(
        @Param("pairingCodeHash") byte[] pairingCodeHash);
    NativeConnectSession getByHandoffTicketHash(
        @Param("handoffTicketHash") byte[] handoffTicketHash);
    NativeConnectSession getByHandoffTicketHashForUpdate(
        @Param("handoffTicketHash") byte[] handoffTicketHash);
    int insert(NativeConnectSession session);
    int failActiveForUserAndProvider(
        @Param("userId") int userId,
        @Param("provider") String provider,
        @Param("errorCode") String errorCode);
    int prepare(
        @Param("id") int id,
        @Param("pairingCodeHash") byte[] pairingCodeHash,
        @Param("handoffTicketHash") byte[] handoffTicketHash,
        @Param("stateHash") byte[] stateHash,
        @Param("verifierRef") String verifierRef,
        @Param("redirectUri") String redirectUri);
    int claimForExchange(
        @Param("id") int id,
        @Param("handoffTicketHash") byte[] handoffTicketHash);
    int complete(@Param("id") int id);
    int fail(
        @Param("id") int id,
        @Param("expectedStatus") String expectedStatus,
        @Param("errorCode") String errorCode);
    List<NativeConnectSession> findTerminalForUserAndProvider(
        @Param("userId") int userId, @Param("provider") String provider);
    int deleteTerminalById(@Param("id") int id);
    List<NativeConnectSession> findExpiredBefore(
        @Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
    int deleteExpired(
        @Param("id") int id, @Param("cutoff") LocalDateTime cutoff);
}
