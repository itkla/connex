package ooo.klae.connex.backend.seeder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;

import ooo.klae.connex.backend.BackendApplication;

/**
 * Launches the volume seeder as a non-web application before its environment is created.
 */
public final class SeederApplication {

    private SeederApplication() {
    }

    /**
     * Starts the guarded volume seeder.
     *
     * @param arguments seeder command-line arguments
     */
    public static void main(String[] arguments) {
        createSpringApplication(BackendApplication.class).run(arguments);
    }

    static SpringApplication createSpringApplication(Class<?>... primarySources) {
        SpringApplication application = new SpringApplication(primarySources);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(new FinalSeederConfigurationInitializer());
        return application;
    }

    private static final class FinalSeederConfigurationInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            SeederStartupConfigurationValidator.validate(
                applicationContext.getEnvironment()
            );
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }
}
