package ooo.klae.connex.backend.notifications;

import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * The STOMP principal a realtime socket is routed by.
 *
 * <p>{@link #userId()} is the immutable {@code app_user.id} the handshake authenticated, and every
 * server-side check — delivery validation, rename and revocation closes — keys on it. It is never
 * put on the wire.
 *
 * <p>{@link #getName()} is what Spring's user-destination resolution writes into broker
 * destinations, what {@code SimpUserRegistry} indexes, and what {@code StompSubProtocolHandler}
 * echoes to the browser in the {@code CONNECTED} frame's {@code user-name} header. There is no
 * supported hook to suppress that header, so the name itself carries no account identifier:
 * {@link RealtimeRoutingIdentityResolver} derives an opaque token from the account id instead.
 * Routing therefore stays bound to the immutable account without disclosing it, which
 * {@code backend/AGENTS.md} requires of principal identifiers.
 *
 * @param userId the immutable account the socket belongs to
 * @param routingName the opaque destination token derived from {@code userId}
 */
public record RealtimeRoutingIdentity(int userId, String routingName) implements AuthenticatedPrincipal {

    @Override
    public String getName() {
        return routingName;
    }
}
