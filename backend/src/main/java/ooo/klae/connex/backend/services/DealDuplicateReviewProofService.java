package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.DealDuplicateReviewProofMapper;

/** Issues and atomically consumes tenant-scoped deal duplicate review proofs. */
@Service
@RequiredArgsConstructor
public class DealDuplicateReviewProofService {
    private static final int TOKEN_BYTES = 32;
    private static final int EXPIRED_PROOF_CLEANUP_LIMIT = 100;

    private final DealDuplicateReviewProofMapper mapper;
    private final WorkspaceService workspaceService;
    private final DuplicatePreflightProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Persists a new opaque proof bound to the current workspace, actor, workflow, and result. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String issue(String workflowFingerprint, String resultFingerprint) {
        Principal principal = principal();
        String rawToken = newToken();
        mapper.deleteExpired(
            principal.workspaceId(),
            EXPIRED_PROOF_CLEANUP_LIMIT);
        if (mapper.insert(
                digest(rawToken),
                principal.workspaceId(),
                principal.actorId(),
                fingerprintBytes(workflowFingerprint, "workflow fingerprint"),
                fingerprintBytes(resultFingerprint, "result fingerprint"),
                properties.getReviewProofTtl().toSeconds()) != 1) {
            throw new IllegalStateException("Deal duplicate review proof was not persisted");
        }
        return rawToken;
    }

    /** Atomically consumes the exact unexpired proof for the current workspace and actor. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean consume(
            String rawToken,
            String workflowFingerprint,
            String resultFingerprint) {
        byte[] tokenHash = tokenHash(rawToken);
        if (tokenHash == null) {
            return false;
        }
        Principal principal = principal();
        Integer claimed = mapper.lockConsumable(
            tokenHash,
            principal.workspaceId(),
            principal.actorId(),
            fingerprintBytes(workflowFingerprint, "workflow fingerprint"),
            fingerprintBytes(resultFingerprint, "result fingerprint"));
        return claimed != null
            && mapper.deleteClaimed(tokenHash, principal.workspaceId()) == 1;
    }

    private Principal principal() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        if (workspaceId <= 0 || actorId <= 0) {
            throw new IllegalStateException("Duplicate-preflight principal is unavailable");
        }
        return new Principal(workspaceId, actorId);
    }

    private String newToken() {
        byte[] token = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(token);
        return HexFormat.of().formatHex(token);
    }

    private static byte[] tokenHash(String rawToken) {
        if (rawToken == null || rawToken.length() != TOKEN_BYTES * 2) {
            return null;
        }
        for (int index = 0; index < rawToken.length(); index++) {
            char character = rawToken.charAt(index);
            if (character < '0'
                    || character > '9' && character < 'a'
                    || character > 'f') {
                return null;
            }
        }
        try {
            HexFormat.of().parseHex(rawToken);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        return digest(rawToken);
    }

    private static byte[] fingerprintBytes(String fingerprint, String label) {
        if (fingerprint == null || fingerprint.length() != TOKEN_BYTES * 2) {
            throw new IllegalArgumentException(
                "Duplicate-preflight " + label + " must be 64 lowercase hexadecimal characters");
        }
        for (int index = 0; index < fingerprint.length(); index++) {
            char character = fingerprint.charAt(index);
            if (character < '0'
                    || character > '9' && character < 'a'
                    || character > 'f') {
                throw new IllegalArgumentException(
                    "Duplicate-preflight " + label
                        + " must be 64 lowercase hexadecimal characters");
            }
        }
        return HexFormat.of().parseHex(fingerprint);
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Principal(int workspaceId, int actorId) {
    }
}
