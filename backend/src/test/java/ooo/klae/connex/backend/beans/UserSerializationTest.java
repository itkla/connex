package ooo.klae.connex.backend.beans;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;

import org.junit.jupiter.api.Test;

/**
 * Pins the serialized identity of the session principal.
 *
 * <p>{@code User} is stored in {@code SPRING_SESSION_ATTRIBUTES} as part of the security context, so
 * its {@code serialVersionUID} is a compatibility boundary for every live session. Changing the
 * declared value orphans them all; this makes that a deliberate act rather than a side effect.
 */
class UserSerializationTest {

    private static final long PINNED_SERIAL_VERSION_UID = -5201556527847944016L;

    @Test
    void theSerializedIdentityIsPinned() {
        assertEquals(PINNED_SERIAL_VERSION_UID, ObjectStreamClass.lookup(User.class).getSerialVersionUID());
    }

    @Test
    void aPrincipalSurvivesASessionRoundTrip() throws Exception {
        User user = new User();
        user.setId(4242);
        user.setUsername("serialization-test");
        user.setSessionEpoch(3);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(user);
        }
        User restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (User) in.readObject();
        }

        assertEquals(user.getId(), restored.getId());
        assertEquals(user.getUsername(), restored.getUsername());
        assertEquals(null, restored.getSessionEpoch(), "the epoch is transient by design");
    }
}
