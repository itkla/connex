package ooo.klae.connex.backend.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

class JavaMailSenderFactoryTest {

    @Test
    void authenticatedConfigsAreNotCachedWithPlaintextPasswords() throws Exception {
        JavaMailSenderFactory factory = new JavaMailSenderFactory();
        ResolvedMailConfig first = config("secret-one");
        ResolvedMailConfig second = config("secret-one");

        factory.forConfig(first);
        factory.forConfig(second);

        assertEquals(0, cacheSize(factory));
    }

    @Test
    void unauthenticatedConfigsAreCached() throws Exception {
        JavaMailSenderFactory factory = new JavaMailSenderFactory();

        factory.forConfig(config(null));
        factory.forConfig(config(null));

        assertEquals(1, cacheSize(factory));
    }

    @Test
    void resolvedMailConfigToStringRedactsPassword() {
        String rendered = config("secret-one").toString();

        assertFalse(rendered.contains("secret-one"));
        assertFalse(rendered.contains("password=secret"));
    }

    private static ResolvedMailConfig config(String password) {
        return new ResolvedMailConfig("smtp.example.com", 587, "user", password,
                "no-reply@example.com", "Connex", true, false, password != null,
                1000, 1000, 1000);
    }

    private static int cacheSize(JavaMailSenderFactory factory) throws Exception {
        Field field = JavaMailSenderFactory.class.getDeclaredField("cache");
        field.setAccessible(true);
        ConcurrentHashMap<?, ?> cache = (ConcurrentHashMap<?, ?>) field.get(factory);
        return cache.size();
    }
}
