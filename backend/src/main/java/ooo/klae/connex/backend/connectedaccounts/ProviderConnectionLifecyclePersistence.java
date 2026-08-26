package ooo.klae.connex.backend.connectedaccounts;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

/**
 * Generation-checked local credential and connection deletion.
 */
@Component
@RequiredArgsConstructor
public class ProviderConnectionLifecyclePersistence {
    private final UserMapper userMapper;
    private final ProviderConnectionMapper connectionMapper;
    private final UserProviderSecretCipher secretCipher;

    /** Deletes only the disconnect generation that completed every tenant purge. */
    @Transactional
    public boolean finish(ProviderConnection expected) {
        if (userMapper.lockByIdForShare(expected.getUserId()) == null) {
            return false;
        }
        ProviderConnection locked = connectionMapper.getByIdForUpdate(expected.getId());
        if (locked == null) {
            return true;
        }
        if (locked.getCredentialGeneration() != expected.getCredentialGeneration()
                || (!"disconnecting".equals(locked.getStatus())
                    && !"purge_failed".equals(locked.getStatus()))) {
            return false;
        }
        if (locked.getCredentialRef() != null) {
            secretCipher.deleteTokenBundleReference(
                locked.getProvider(), locked.getUserId(), locked.getCredentialRef());
        }
        return connectionMapper.deleteGeneration(
            locked.getId(), locked.getCredentialGeneration()) == 1;
    }

    /** Destroys the credential and retains a generation-bearing disconnected tombstone. */
    @Transactional
    public boolean finishRevocation(ProviderConnection expected) {
        if (userMapper.lockByIdForShare(expected.getUserId()) == null) {
            return false;
        }
        ProviderConnection locked = connectionMapper.getByIdForUpdate(expected.getId());
        if (locked == null
                || locked.getCredentialGeneration() != expected.getCredentialGeneration()
                || !"revoking".equals(locked.getStatus())) {
            return false;
        }
        if (locked.getCredentialRef() != null) {
            secretCipher.deleteTokenBundleReference(
                locked.getProvider(), locked.getUserId(), locked.getCredentialRef());
        }
        return connectionMapper.completeRevocation(
            locked.getId(), locked.getCredentialGeneration()) == 1;
    }
}
