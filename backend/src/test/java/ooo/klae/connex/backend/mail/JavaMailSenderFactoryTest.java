package ooo.klae.connex.backend.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

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

    @Test
    void pinnedConfigsUsePinnedSocketWithoutCaching() throws Exception {
        JavaMailSenderFactory factory = new JavaMailSenderFactory();
        InetAddress address = InetAddress.getByName("203.0.113.10");

        JavaMailSenderImpl sender = assertInstanceOf(
            JavaMailSenderImpl.class, factory.forConfig(config(null), address));

        assertInstanceOf(PinnedSocketFactory.class,
            sender.getJavaMailProperties().get("mail.smtp.socketFactory"));
        assertEquals("false", sender.getJavaMailProperties().get("mail.smtp.socketFactory.fallback"));
        assertEquals(0, cacheSize(factory));
    }

    @Test
    void tlsTransportRequiresUpgradeAndChecksServerIdentity() {
        JavaMailSenderFactory factory = new JavaMailSenderFactory();
        JavaMailSenderImpl sender = assertInstanceOf(JavaMailSenderImpl.class, factory.forConfig(config(null)));

        assertEquals("true", sender.getJavaMailProperties().get("mail.smtp.starttls.required"));
        assertEquals("true", sender.getJavaMailProperties().get("mail.smtp.ssl.checkserveridentity"));
        assertTrue(Boolean.parseBoolean(sender.getJavaMailProperties().getProperty("mail.smtp.starttls.enable")));
    }

    private static ResolvedMailConfig config(String password) {
        return new ResolvedMailConfig("smtp.example.com", 587, "user", password,
                "no-reply@example.com", "Connex", true, false, password != null,
                1000, 1000, 1000, false);
    }

    private static int cacheSize(JavaMailSenderFactory factory) throws Exception {
        Field field = JavaMailSenderFactory.class.getDeclaredField("cache");
        field.setAccessible(true);
        ConcurrentHashMap<?, ?> cache = (ConcurrentHashMap<?, ?>) field.get(factory);
        return cache.size();
    }
}
