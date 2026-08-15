package ooo.klae.connex.backend.connectedaccounts.nativeflow;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.NativeConnectSession;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.connectedaccounts.ProviderAccountIdentityResolver.ProviderAccountIdentity;
import ooo.klae.connex.backend.connectedaccounts.ProviderCredentialPersistence;
import ooo.klae.connex.backend.connectedaccounts.ProviderTokenResponse;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.NativeConnectSessionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

/** Transactional state transitions for managed native authorization sessions. */
@Component
@RequiredArgsConstructor
public class NativeConnectSessionPersistence {
    private static final Set<String> ACTIVE_STATUSES =
        Set.of("pending", "prepared", "exchanging");

    private final NativeConnectSessionMapper sessionMapper;
    private final UserMapper userMapper;
    private final NativeConnectPkceSecretCipher pkceSecretCipher;
    private final ProviderCredentialPersistence credentialPersistence;
    private final Clock clock;

    /** Supersedes the user's active provider session and inserts a fresh pending session. */
    @Transactional
    public boolean create(
            int userId,
            String provider,
            byte[] pairingCodeHash,
            LocalDateTime expiresAt) {
        requireUser(userId);
        NativeConnectSession previous =
            sessionMapper.getLatestByUserAndProviderForUpdate(userId, provider);
        boolean superseded = sessionMapper.failActiveForUserAndProvider(
            userId, provider, "superseded") > 0;
        if (previous != null && ACTIVE_STATUSES.contains(previous.getStatus())) {
            deleteVerifier(previous);
        }
        NativeConnectSession session = new NativeConnectSession();
        session.setUserId(userId);
        session.setProvider(provider);
        session.setStatus("pending");
        session.setPairingCodeHash(pairingCodeHash);
        session.setExpiresAt(expiresAt);
        sessionMapper.insert(session);
        return superseded;
    }

    /** Returns the current user's latest provider session, expiring it atomically when due. */
    @Transactional
    public NativeConnectPoll poll(int userId, String provider) {
        requireReadableUser(userId);
        NativeConnectSession session =
            sessionMapper.getLatestByUserAndProvider(userId, provider);
        boolean expiredTransition = false;
        if (session != null && ACTIVE_STATUSES.contains(session.getStatus()) && expired(session)) {
            requireUser(userId);
            session = sessionMapper.getLatestByUserAndProviderForUpdate(userId, provider);
            if (session != null
                    && ACTIVE_STATUSES.contains(session.getStatus())
                    && expired(session)
                    && sessionMapper.fail(
                        session.getId(), session.getStatus(), "expired") == 1) {
                deleteVerifier(session);
                session.setStatus("failed");
                session.setErrorCode("expired");
                expiredTransition = true;
            }
        }
        return new NativeConnectPoll(session, expiredTransition);
    }

    /** Cancels only the current user's active provider session. */
    @Transactional
    public boolean cancel(int userId, String provider) {
        requireUser(userId);
        NativeConnectSession session =
            sessionMapper.getLatestByUserAndProviderForUpdate(userId, provider);
        if (session == null || !ACTIVE_STATUSES.contains(session.getStatus())) {
            return false;
        }
        if (sessionMapper.fail(session.getId(), session.getStatus(), "cancelled") != 1) {
            return false;
        }
        deleteVerifier(session);
        return true;
    }

    /** Looks up a session by the hash of its bearer-grade pairing credential. */
    public NativeConnectSession findByPairingCodeHash(byte[] pairingCodeHash) {
        return sessionMapper.getByPairingCodeHash(pairingCodeHash);
    }

    /** Looks up a session by the hash of its bearer-grade completion credential. */
    public NativeConnectSession findByHandoffTicketHash(byte[] handoffTicketHash) {
        return sessionMapper.getByHandoffTicketHash(handoffTicketHash);
    }

    /** Atomically claims a pending pairing and persists its encrypted PKCE verifier reference. */
    @Transactional
    public NativeConnectSession prepare(
            byte[] pairingCodeHash,
            byte[] handoffTicketHash,
            byte[] stateHash,
            String verifier,
            String redirectUri) {
        NativeConnectSession candidate =
            sessionMapper.getByPairingCodeHash(pairingCodeHash);
        if (candidate == null) {
            throw invalidPairingCode();
        }
        requireUser(candidate.getUserId());
        NativeConnectSession session =
            sessionMapper.getByPairingCodeHashForUpdate(pairingCodeHash);
        requirePendingPairing(session);
        String verifierRef = pkceSecretCipher.store(
            session.getProvider(), session.getUserId(), verifier);
        int updated = sessionMapper.prepare(
            session.getId(),
            pairingCodeHash,
            handoffTicketHash,
            stateHash,
            verifierRef,
            redirectUri);
        if (updated != 1) {
            throw new NativeConnectException(
                "pairing_already_claimed", "Pairing code has already been claimed");
        }
        session.setStatus("prepared");
        session.setHandoffTicketHash(handoffTicketHash);
        session.setStateHash(stateHash);
        session.setVerifierRef(verifierRef);
        session.setRedirectUri(redirectUri);
        session.setErrorCode(null);
        return session;
    }

    /** Atomically claims a prepared handoff for provider token exchange. */
    @Transactional
    public NativeConnectSession claimForExchange(byte[] handoffTicketHash) {
        NativeConnectSession candidate =
            sessionMapper.getByHandoffTicketHash(handoffTicketHash);
        if (candidate == null) {
            throw invalidHandoffTicket();
        }
        requireUser(candidate.getUserId());
        NativeConnectSession session =
            sessionMapper.getByHandoffTicketHashForUpdate(handoffTicketHash);
        requirePreparedHandoff(session);
        if (sessionMapper.claimForExchange(session.getId(), handoffTicketHash) != 1) {
            throw new NativeConnectException(
                "handoff_already_used", "Handoff ticket has already been used");
        }
        session.setStatus("exchanging");
        session.setErrorCode(null);
        return session;
    }

    /** Stores the provider credential and consumes the claimed native session in one transaction. */
    @Transactional
    public boolean storeConnectionAndComplete(
            NativeConnectSession session,
            ProviderTokenResponse tokens,
            ProviderAccountIdentity identity,
            String grantedScopes) {
        boolean created = credentialPersistence.storeConnection(
            session.getUserId(),
            session.getProvider(),
            tokens,
            identity.accountId(),
            identity.email(),
            grantedScopes);
        if (sessionMapper.complete(session.getId()) != 1) {
            throw new ConflictException("Native authorization session is no longer exchangeable");
        }
        deleteVerifier(session);
        return created;
    }

    /** Fails an exchange claim and destroys its retained verifier. */
    @Transactional
    public void failExchange(NativeConnectSession session, String errorCode) {
        requireUser(session.getUserId());
        if (sessionMapper.fail(session.getId(), "exchanging", errorCode) == 1) {
            deleteVerifier(session);
        }
    }

    /** Finds a bounded batch of sessions beyond the expired-session retention grace. */
    public List<NativeConnectSession> findExpiredBefore(
            LocalDateTime cutoff, int limit) {
        return sessionMapper.findExpiredBefore(cutoff, limit);
    }

    /** Deletes one still-expired session after its verifier secret is removed. */
    @Transactional
    public boolean deleteExpired(
            int sessionId, int userId, LocalDateTime cutoff) {
        if (userMapper.lockByIdForShare(userId) == null) {
            return false;
        }
        NativeConnectSession session = sessionMapper.getByIdForUpdate(sessionId);
        if (session == null
                || session.getUserId() != userId
                || session.getExpiresAt() == null
                || session.getExpiresAt().isAfter(cutoff)) {
            return false;
        }
        deleteVerifier(session);
        return sessionMapper.deleteExpired(sessionId, cutoff) == 1;
    }

    private void requirePendingPairing(NativeConnectSession session) {
        if (session == null) {
            throw invalidPairingCode();
        }
        if ("expired".equals(session.getErrorCode())) {
            throw new NativeConnectException(
                "pairing_expired", "Pairing code has expired");
        }
        if (!"pending".equals(session.getStatus())) {
            throw new NativeConnectException(
                "pairing_already_claimed", "Pairing code has already been claimed");
        }
        if (expired(session)) {
            throw new NativeConnectException(
                "pairing_expired", "Pairing code has expired");
        }
    }

    private void requirePreparedHandoff(NativeConnectSession session) {
        if (session == null) {
            throw invalidHandoffTicket();
        }
        if ("expired".equals(session.getErrorCode())) {
            throw new NativeConnectException(
                "handoff_expired", "Handoff ticket has expired");
        }
        if (!"prepared".equals(session.getStatus())) {
            throw new NativeConnectException(
                "handoff_already_used", "Handoff ticket has already been used");
        }
        if (expired(session)) {
            throw new NativeConnectException(
                "handoff_expired", "Handoff ticket has expired");
        }
    }

    private boolean expired(NativeConnectSession session) {
        return session.getExpiresAt() == null
            || !session.getExpiresAt().isAfter(
                LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC));
    }

    private void requireUser(int userId) {
        if (userMapper.lockById(userId) == null) {
            throw new ResourceNotFoundException("User not found: " + userId);
        }
        if (userMapper.isAccountDeletionReserved(userId)) {
            throw new ConflictException("Account deletion is in progress");
        }
    }

    private void requireReadableUser(int userId) {
        User user = userMapper.getUserById(userId);
        if (user == null) {
            throw new ResourceNotFoundException("User not found: " + userId);
        }
        if (userMapper.isAccountDeletionReserved(userId)) {
            throw new ConflictException("Account deletion is in progress");
        }
    }

    private void deleteVerifier(NativeConnectSession session) {
        if (session != null
                && session.getVerifierRef() != null
                && !session.getVerifierRef().isBlank()) {
            pkceSecretCipher.delete(
                session.getProvider(), session.getUserId(), session.getVerifierRef());
        }
    }

    private static NativeConnectException invalidPairingCode() {
        return new NativeConnectException(
            "invalid_pairing_code", "Pairing code is invalid");
    }

    private static NativeConnectException invalidHandoffTicket() {
        return new NativeConnectException(
            "invalid_handoff_ticket", "Handoff ticket is invalid");
    }
}
