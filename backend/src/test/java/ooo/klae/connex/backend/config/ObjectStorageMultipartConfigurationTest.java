package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.util.unit.DataSize;

class ObjectStorageMultipartConfigurationTest {
    @Test
    void globalMultipartCeilingDoesNotApplyScannerSpecificEightMegabyteLimit() {
        MultipartConfigurationTestSupport.nonWebContextRunner().run(context -> {
            MultipartProperties multipartProperties = Binder.get(context.getEnvironment())
                    .bind("spring.servlet.multipart", Bindable.of(MultipartProperties.class))
                    .orElseThrow(() -> new IllegalStateException("Multipart properties are not configured"));

            assertTrue(multipartProperties.getMaxFileSize().compareTo(DataSize.ofMegabytes(8)) > 0);
            assertTrue(multipartProperties.getMaxRequestSize().compareTo(DataSize.ofMegabytes(8)) > 0);
        });
    }
}
