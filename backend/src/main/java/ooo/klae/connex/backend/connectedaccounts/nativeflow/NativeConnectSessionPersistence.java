package ooo.klae.connex.backend.connectedaccounts.nativeflow;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.NativeConnectSession;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.connectedaccounts.ProviderAccountIdentityResolver.ProviderAccountIdentity;
import ooo.klae.connex.backend.connectedaccounts.ProviderConnectionExpectation;
import ooo.klae.connex.backend.connectedaccounts.ProviderCredentialPersistence;
import ooo.klae.connex.backend.connectedaccounts.ProviderTokenResponse;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.NativeConnectSessionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

/** Transactional state transitions for managed native authorization sessions. */
@Component
public class NativeConnectSessionPersistence {
    private static final Set<String> CANCELLABLE_STATUSES = Set.of("pending", "prepared");

    private final NativeConnectSessionMapper sessionMapper;
    private final UserMapper userMapper;
    private final NativeConnectPkceSecretCipher pkceSecretCipher;
    private final ProviderCredentialPersistence credentialPersistence;
    private final int exchangeLeaseSeconds;

    /** Configures a bounded exchange lease measured from the database claim time. */
    public NativeConnectSessionPersistence(
            NativeConnectSessionMapper sessionMapper,
            UserMapper userMapper,
            NativeConnectPkceSecretCipher pkceSecretCipher,
            ProviderCredentialPersistence credentialPersistence,
            @Value("${connex.connected-accounts.native.exchange-lease-seconds:120}")
                int exchangeLeaseSeconds) {
        if (exchangeLeaseSeconds < 1 || exchangeLeaseSeconds > 600) {
            throw new IllegalArgumentException("Native exchange lease must be between 1 and 600 seconds");
        }
        this.sessionMapper = sessionMapper;
        this.userMapper = userMapper;
        this.pkceSecretCipher = pkceSecretCipher;
        this.credentialPersistence = credentialPersistence;
        this.exchangeLeaseSeconds = exchangeLeaseSeconds;
    }

    /**
     * Supersedes the user's unclaimed provider session and inserts a fresh pending session. In-flight
     * exchanges retain their lease and verifier until completion, failure, or scheduled recovery. Terminal
     * sessions for the same user and provider are reclaimed here rather than waiting for the
     * scheduled cleanup. Abandoned exchanges are recovered by that bounded cleanup sweep.
     */
    @Transactional
    public boolean create(
            int userId,
            String provider,
            byte[] pairingCodeHash,
            LocalDateTime expiresAt) {
        requireUser(userId);
        ProviderConnectionExpectation expectation =
            credentialPersistence.authorizationExpectation(userId, provider);
        NativeConnectSession previous =
            sessionMapper.getLatestByUserAndProviderForUpdate(userId, provider);
        boolean superseded = sessionMapper.failActiveForUserAndProvider(
            userId, provider, "superseded") > 0;
        if (superseded && previous != null && CANCELLABLE_STATUSES.contains(previous.getStatus())) {
            deleteVerifier(previous);
        }
        for (NativeConnectSession terminal
                : sessionMapper.findTerminalForUserAndProvider(userId, provider)) {
            deleteVerifier(terminal);
            sessionMapper.deleteTerminalById(terminal.getId());
        }
        NativeConnectSession session = new NativeConnectSession();
        session.setUserId(userId);
        session.setProvider(provider);
        session.setStatus("pending");
        session.setPairingCodeHash(pairingCodeHash);
        session.setExpectedConnectionId(
            expectation.present() ? expectation.connectionId() : null);
        session.setExpectedCredentialGeneration(
            expectation.present() ? expectation.credentialGeneration() : null);
        session.setExpiresAt(expiresAt);
        sessionMapper.insert(session);
        return superseded;
    }

    /**
     * Reads the current user's latest provider session without mutating it. An active session past
     * its expiry is reported as failed for display only; the claim paths re-check expiry in SQL and
     * {@link NativeConnectSessionCleanup} reclaims the row and its verifier, so this read never
     * needs to write. Keeping it side-effect free means the status endpoint stays a true GET.
     */
    public NativeConnectSession poll(int userId, String provider) {
        requireReadableUser(userId);
        NativeConnectSession session =
            sessionMapper.getLatestByUserAndProvider(userId, provider);
        if (session != null && CANCELLABLE_STATUSES.contains(session.getStatus()) && session.isExpired()) {
            session.setStatus("failed");
            session.setErrorCode("expired");
        }
        return session;
    }

    /** Cancels only the current user's provider session before exchange has been claimed. */
    @Transactional
    public boolean cancel(int userId, String provider) {
        requireUser(userId);
        NativeConnectSession session =
            sessionMapper.getLatestByUserAndProviderForUpdate(userId, provider);
        if (session == null || !CANCELLABLE_STATUSES.contains(session.getStatus())) {
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
            requirePendingPairing(sessionMapper.getByPairingCodeHashForUpdate(pairingCodeHash));
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
            requirePreparedHandoff(sessionMapper.getByHandoffTicketHashForUpdate(handoffTicketHash));
            throw new NativeConnectException(
                "handoff_already_used", "Handoff ticket has already been used");
        }
        session.setStatus("exchanging");
        session.setErrorCode(null);
        return session;
    }

    /** Rejects a claimed exchange when its connection boundary is already stale. */
    @Transactional
    public void requireConnectionExpectation(NativeConnectSession session) {
        credentialPersistence.requireAuthorizationExpectation(
            session.getUserId(),
            session.getProvider(),
            ProviderConnectionExpectation.persisted(
                session.getExpectedConnectionId(),
                session.getExpectedCredentialGeneration()));
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
            ProviderConnectionExpectation.persisted(
                session.getExpectedConnectionId(),
                session.getExpectedCredentialGeneration()),
            tokens,
            identity.accountId(),
            identity.email(),
            grantedScopes);
        if (sessionMapper.complete(session.getId(), exchangeLeaseSeconds) != 1) {
            throw new ConflictException("Native authorization session is no longer exchangeable");
        }
        deleteVerifier(session);
        return created;
    }

    /** Fails an exchange claim and destroys its retained verifier only when this transition wins. */
    @Transactional
    public boolean failExchange(NativeConnectSession session, String errorCode) {
        requireUser(session.getUserId());
        if (sessionMapper.fail(session.getId(), "exchanging", errorCode) == 1) {
            deleteVerifier(session);
            return true;
        }
        return false;
    }

    /** Finds every abandoned exchange, including sessions older than the user's latest pairing. */
    public List<NativeConnectSession> findExpiredExchanges(int limit) {
        return sessionMapper.findExpiredExchanges(exchangeLeaseSeconds, limit);
    }

    /** Recovers one expired exchange under the user root before the exact session lock. */
    @Transactional
    public boolean failExpiredExchange(int sessionId, int userId) {
        if (userMapper.lockByIdForShare(userId) == null) {
            return false;
        }
        NativeConnectSession session = sessionMapper.getByIdForUpdate(sessionId);
        if (session == null || session.getUserId() != userId
                || sessionMapper.failExpiredExchange(sessionId, exchangeLeaseSeconds) != 1) {
            return false;
        }
        deleteVerifier(session);
        return true;
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
                || "exchanging".equals(session.getStatus())
                || session.getExpiresAt() == null
                || session.getExpiresAt().isAfter(cutoff)
                || session.getUpdatedAt() == null
                || session.getUpdatedAt().isAfter(cutoff)) {
            return false;
        }
        deleteVerifier(session);
        return sessionMapper.deleteExpired(sessionId, cutoff) == 1;
    }

    private void requirePendingPairing(NativeConnectSession session) {
        if (session == null) {
            throw invalidPairingCode();
        }
        if ("expired".equals(session.getErrorCode()) || session.isExpired()) {
            throw new NativeConnectException(
                "pairing_expired", "Pairing code has expired");
        }
        if (!"pending".equals(session.getStatus())) {
            throw new NativeConnectException(
                "pairing_already_claimed", "Pairing code has already been claimed");
        }
    }

    private void requirePreparedHandoff(NativeConnectSession session) {
        if (session == null) {
            throw invalidHandoffTicket();
        }
        if ("expired".equals(session.getErrorCode()) || session.isExpired()) {
            throw new NativeConnectException(
                "handoff_expired", "Handoff ticket has expired");
        }
        if (!"prepared".equals(session.getStatus())) {
            throw new NativeConnectException(
                "handoff_already_used", "Handoff ticket has already been used");
        }
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
