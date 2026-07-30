package ooo.klae.connex.backend.seeder;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

public final class SeederDevToolsIsolationProbe {

    private static final AtomicBoolean CANARY_INITIALIZED = new AtomicBoolean();

    private SeederDevToolsIsolationProbe() {
    }

    public static void main(String[] arguments) {
        try (ConfigurableApplicationContext context =
                SeederApplication.createSpringApplication(ProbeApplication.class).run(
                    "--spring.profiles.active=seeder",
                    "--spring.main.web-application-type=none",
                    "--connex.seeder.enabled=true",
                    "--spring.datasource.url="
                        + "jdbc:mysql://127.0.0.1:1/connex_seeder_probe?sslMode=DISABLED",
                    "--spring.datasource.username=seeder-probe",
                    "--spring.datasource.password=seeder-probe"
                )) {
            if (CANARY_INITIALIZED.get() || !context.isActive()) {
                throw new AssertionError(
                    "Seeder runtime did not remain isolated from DevTools home configuration"
                );
            }
        }
    }

    static class ProbeApplication {
    }

    public static final class CanaryInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            CANARY_INITIALIZED.set(true);
            throw new AssertionError(
                "Seeder runtime loaded Spring DevTools home configuration"
            );
        }
    }
}
