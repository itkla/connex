package ooo.klae.connex.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.spi.InitialContextFactory;

import org.junit.jupiter.api.Test;
import org.springframework.core.SpringProperties;
import org.springframework.mock.web.MockServletContext;

class BackendApplicationStartupTest {

    private static final AtomicInteger JNDI_INITIALIZATIONS = new AtomicInteger();
    private static final AtomicInteger JNDI_CONSTRUCTIONS = new AtomicInteger();
    private static final String CANARY_INITIAL_CONTEXT_FACTORY =
        "ooo.klae.connex.backend.BackendApplicationStartupTest$CanaryInitialContextFactory";

    @Test
    void executableBackendApplicationRefusesSeederSignalsBeforeJndi() {
        String previousFactory = System.getProperty(Context.INITIAL_CONTEXT_FACTORY);
        resetCanaries();
        try {
            System.setProperty(
                Context.INITIAL_CONTEXT_FACTORY,
                CANARY_INITIAL_CONTEXT_FACTORY
            );
            assertTrue(SpringProperties.getFlag("spring.jndi.ignore"));

            RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> BackendApplication.main(
                    new String[] {"--connex.seeder.enabled=true"}
                )
            );

            assertTrue(refusalMessage(exception).contains("SeederApplication launcher"));
            assertNoJndiActivity();
        } finally {
            restoreSystemProperty(Context.INITIAL_CONTEXT_FACTORY, previousFactory);
        }
    }

    @Test
    void servletInitializerRefusesSeederSignalsBeforeJndi() {
        String previousFactory = System.getProperty(Context.INITIAL_CONTEXT_FACTORY);
        String previousSeederSignal = System.getProperty("connex.seeder.enabled");
        resetCanaries();
        try {
            System.setProperty(
                Context.INITIAL_CONTEXT_FACTORY,
                CANARY_INITIAL_CONTEXT_FACTORY
            );
            System.setProperty("connex.seeder.enabled", "true");

            RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> new ServletInitializer().onStartup(new MockServletContext())
            );

            assertTrue(refusalMessage(exception).contains("SeederApplication launcher"));
            assertNoJndiActivity();
        } finally {
            restoreSystemProperty("connex.seeder.enabled", previousSeederSignal);
            restoreSystemProperty(Context.INITIAL_CONTEXT_FACTORY, previousFactory);
        }
    }

    private static void resetCanaries() {
        JNDI_INITIALIZATIONS.set(0);
        JNDI_CONSTRUCTIONS.set(0);
    }

    private static void assertNoJndiActivity() {
        assertEquals(0, JNDI_INITIALIZATIONS.get());
        assertEquals(0, JNDI_CONSTRUCTIONS.get());
    }

    private static String refusalMessage(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current.getMessage() != null
                    && current.getMessage().startsWith("Seeder refused:")) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return "";
    }

    private static void restoreSystemProperty(String propertyName, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(propertyName);
        } else {
            System.setProperty(propertyName, previousValue);
        }
    }

    static final class CanaryInitialContextFactory implements InitialContextFactory {

        static {
            JNDI_INITIALIZATIONS.incrementAndGet();
        }

        CanaryInitialContextFactory() {
            JNDI_CONSTRUCTIONS.incrementAndGet();
        }

        @Override
        public Context getInitialContext(java.util.Hashtable<?, ?> environment)
                throws NamingException {
            throw new NamingException("canary");
        }
    }
}
