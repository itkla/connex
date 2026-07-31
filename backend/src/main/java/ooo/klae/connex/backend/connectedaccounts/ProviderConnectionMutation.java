package ooo.klae.connex.backend.connectedaccounts;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.dto.ProviderConnectionDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;

/**
 * Control-catalog transaction boundary for provider connection state changes.
 */
@Component
@RequiredArgsConstructor
public class ProviderConnectionMutation {
    private final ProviderConnectionMapper connectionMapper;

    /** Applies an exact expected-state pause or resume transition. */
    @Transactional
    public ProviderConnectionDto transition(
            int userId, String provider, String from, String to) {
        ProviderConnection connection =
            connectionMapper.getByUserAndProviderForUpdate(userId, provider);
        if (connection == null) {
            throw new ResourceNotFoundException("No " + provider + " connection");
        }
        if (!from.equals(connection.getStatus())) {
            throw new BadRequestException(
                "Connection is " + connection.getStatus() + ", not " + from);
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

    /** Advances one connection into a generation-invalidating disconnect state. */
    @Transactional
    public ProviderConnection beginDisconnect(int userId, String provider) {
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
}
