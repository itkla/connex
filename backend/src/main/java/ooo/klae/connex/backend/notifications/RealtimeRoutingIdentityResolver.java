package ooo.klae.connex.backend.notifications;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Derives the opaque STOMP destination token an account's realtime sockets are addressed by.
 *
 * <p>The handshake binds a socket to the token and the publishers address the same token, so
 * delivery stays keyed to the immutable account id while nothing the browser can observe — the
 * {@code CONNECTED} frame's {@code user-name} header in particular — discloses it.
 *
 * <p>The key is fresh per JVM rather than configured. Routing only has to be deterministic for as
 * long as a socket lives, the broker is the in-memory single-JVM one, and delivery is refused
 * outright for any transport this instance does not hold ({@link WebSocketSessionExpiryInterceptor}).
 * A per-instance key therefore costs nothing, adds no deployment secret that could be missing, and
 * makes the token uncorrelatable across restarts. A cross-instance broker would replace the
 * publisher seam and would have to supply a shared key here at the same time.
 */
@Component
public class RealtimeRoutingIdentityResolver {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int KEY_LENGTH = 32;
    private static final byte[] DOMAIN =
        "connex.realtime.user-destination.v1\0".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec key;

    public RealtimeRoutingIdentityResolver() {
        byte[] material = new byte[KEY_LENGTH];
        new SecureRandom().nextBytes(material);
        this.key = new SecretKeySpec(material, HMAC_ALGORITHM);
    }

    /**
     * Returns the principal an authenticated handshake binds its socket to.
     *
     * @param userId the immutable account the handshake authenticated
     * @return the routing principal for that account
     */
    public RealtimeRoutingIdentity forAccount(int userId) {
        return new RealtimeRoutingIdentity(userId, destinationFor(userId));
    }

    /**
     * Returns the user-destination token that addresses every live socket of an account.
     *
     * @param userId the immutable account to address
     * @return the opaque destination token
     * @throws IllegalArgumentException if {@code userId} is not a persisted account id
     */
    public String destinationFor(int userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("Account id must be positive");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            mac.update(DOMAIN);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal(ByteBuffer.allocate(Integer.BYTES).putInt(userId).array()));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is required for realtime destination routing",
                exception);
        }
    }
}
