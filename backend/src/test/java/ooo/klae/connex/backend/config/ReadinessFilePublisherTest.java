package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.event.ApplicationReadyEvent;

class ReadinessFilePublisherTest {
    @TempDir Path temporaryDirectory;

    @Test
    void publishesConfiguredMarker() throws Exception {
        Path marker = temporaryDirectory.resolve("ready");
        ReadinessFilePublisher publisher = new ReadinessFilePublisher(marker.toString());

        publisher.onApplicationEvent(mock(ApplicationReadyEvent.class));

        assertEquals("ready\n", Files.readString(marker));
    }

    @Test
    void blankConfigurationDoesNotPublish() {
        ReadinessFilePublisher publisher = new ReadinessFilePublisher("");

        publisher.onApplicationEvent(mock(ApplicationReadyEvent.class));

        assertFalse(Files.exists(temporaryDirectory.resolve("ready")));
    }

    @Test
    void rejectsRelativeMarkerPath() {
        ReadinessFilePublisher publisher = new ReadinessFilePublisher("relative-ready");

        assertThrows(
            IllegalStateException.class,
            () -> publisher.onApplicationEvent(mock(ApplicationReadyEvent.class)));
    }
}
