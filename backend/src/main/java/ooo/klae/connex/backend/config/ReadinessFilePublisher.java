package ooo.klae.connex.backend.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Publishes a container-local readiness marker after every startup runner has completed.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ReadinessFilePublisher implements ApplicationListener<ApplicationReadyEvent> {
    private final String configuredPath;

    public ReadinessFilePublisher(@Value("${connex.readiness-file:}") String configuredPath) {
        this.configuredPath = configuredPath;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (configuredPath.isBlank()) {
            return;
        }
        Path path = Path.of(configuredPath);
        if (!path.isAbsolute() || path.getParent() == null || !Files.isDirectory(path.getParent())) {
            throw new IllegalStateException("connex.readiness-file must have an existing absolute parent directory");
        }
        try {
            Files.writeString(
                path,
                "ready\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not publish the application readiness marker", exception);
        }
    }
}
