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
import ooo.klae.connex.backend.session.AccountSessionIndex;

/** Binds STOMP user destinations to the immutable account that authenticated the handshake. */
public class WebSocketAccountHandshakeHandler extends DefaultHandshakeHandler {
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
                new AccountSessionIndex(user.getId()), null, authentication.getAuthorities());
    }
}
