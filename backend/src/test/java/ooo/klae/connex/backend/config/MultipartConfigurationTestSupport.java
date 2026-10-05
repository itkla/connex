package ooo.klae.connex.backend.config;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.servlet.autoconfigure.MultipartAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/** Loads the application's configuration files without its database-backed beans. */
final class MultipartConfigurationTestSupport {
    private MultipartConfigurationTestSupport() {
    }

    static WebApplicationContextRunner servletContextRunner() {
        return new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(MultipartAutoConfiguration.class));
    }

    static ApplicationContextRunner nonWebContextRunner() {
        return new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());
    }
}
