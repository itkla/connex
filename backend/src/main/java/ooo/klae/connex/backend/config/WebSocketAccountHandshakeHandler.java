package ooo.klae.connex.backend.config;

import java.security.Principal;
import java.util.Map;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeFailureException;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.notifications.RealtimeRoutingIdentityResolver;

/**
 * Binds STOMP user destinations to the immutable account that authenticated the handshake.
 * The principal carries the account id for server-side checks and an opaque routing name for the
 * wire, so a recycled username cannot inherit a live socket and the account id is never echoed
 * back to the browser.
 */
public class WebSocketAccountHandshakeHandler extends DefaultHandshakeHandler {

    private final RealtimeRoutingIdentityResolver routingIdentities;

    /**
     * @param routingIdentities the resolver that derives this instance's destination tokens
     */
    public WebSocketAccountHandshakeHandler(RealtimeRoutingIdentityResolver routingIdentities) {
        this.routingIdentities = routingIdentities;
    }

    @Override
    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler handler,
            Map<String, Object> attributes) {
        if (!(request.getPrincipal() instanceof Authentication authentication)
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof User user)
                || user.getId() <= 0) {
            throw new HandshakeFailureException("An authenticated account is required");
        }
        return UsernamePasswordAuthenticationToken.authenticated(
                routingIdentities.forAccount(user.getId()), null, authentication.getAuthorities());
    }
}
