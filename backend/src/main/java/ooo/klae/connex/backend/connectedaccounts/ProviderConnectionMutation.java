package ooo.klae.connex.backend.connectedaccounts;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.dto.ProviderConnectionDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

/**
 * Control-catalog transaction boundary for provider connection state changes.
 */
@Component
@RequiredArgsConstructor
public class ProviderConnectionMutation {
    private final UserMapper userMapper;
    private final ProviderConnectionMapper connectionMapper;

    /** Applies an exact expected-state pause or resume transition. */
    @Transactional
    public ProviderConnectionDto transition(
            int userId, String provider, String from, String to) {
        requireUser(userId);
        ProviderConnection connection =
            connectionMapper.getByUserAndProviderForUpdate(userId, provider);
        if (connection == null) {
            throw new ResourceNotFoundException("No " + provider + " connection");
        }
        if (!from.equals(connection.getStatus())) {
            throw new BadRequestException(
                "Connection cannot transition from its current state");
        }
        connection.setStatus(to);
        connection.setCredentialGeneration(connection.getCredentialGeneration() + 1);
        connection.setCaptureReconcileRequired(true);
        connection.setCaptureReconcileAfterWorkspaceId(0);
        connection.setCaptureReconcileLeaseOwner(null);
        connection.setCaptureReconcileLeaseUntil(null);
        connection.setCaptureReconcileNextAttemptAt(null);
        connection.setCaptureReconcileFailures(0);
        connectionMapper.update(connection);
        return ProviderConnectionDto.from(
            connectionMapper.getByUserAndProvider(userId, provider));
    }

    /** Advances one connection into credential-only revocation without erasing captured data. */
    @Transactional
    public ProviderConnection beginRevocation(int userId, String provider) {
        requireUser(userId);
        ProviderConnection connection =
            connectionMapper.getByUserAndProviderForUpdate(userId, provider);
        if (connection == null) {
            throw new ResourceNotFoundException("No " + provider + " connection");
        }
        if ("disconnected".equals(connection.getStatus())
                || "revoking".equals(connection.getStatus())) {
            return connection;
        }
        if ("disconnecting".equals(connection.getStatus())
                || "purge_failed".equals(connection.getStatus())) {
            throw new ConflictException(
                "Provider account cleanup is already in progress");
        }
        if (connectionMapper.beginRevocation(userId, provider) != 1) {
            throw new BadRequestException(
                "Connection cannot enter credential revocation");
        }
        return connectionMapper.getByUserAndProvider(userId, provider);
    }

    /** Advances one connection into a generation-invalidating disconnect state. */
    @Transactional
    public ProviderConnection beginDisconnect(int userId, String provider) {
        requireUser(userId);
        ProviderConnection connection =
            connectionMapper.getByUserAndProviderForUpdate(userId, provider);
        if (connection == null) {
            throw new ResourceNotFoundException("No " + provider + " connection");
        }
        if (!"disconnecting".equals(connection.getStatus())
                && !"purge_failed".equals(connection.getStatus())
                && connectionMapper.beginDisconnect(userId, provider) != 1) {
            throw new BadRequestException(
                "Connection cannot enter disconnect cleanup");
        }
        return connectionMapper.getByUserAndProvider(userId, provider);
    }

    private void requireUser(int userId) {
        if (userMapper.lockByIdForShare(userId) == null) {
            throw new ResourceNotFoundException("User not found: " + userId);
        }
    }
}
