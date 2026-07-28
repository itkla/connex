package ooo.klae.connex.backend.seeder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

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
        return application;
    }
}
