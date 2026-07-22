package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ObjectStorageMultipartConfigurationTest {
    @Autowired Environment environment;

    @Test
    void globalMultipartCeilingDoesNotApplyScannerSpecificEightMegabyteLimit() {
        MultipartProperties multipartProperties = Binder.get(environment)
                .bind("spring.servlet.multipart", Bindable.of(MultipartProperties.class))
                .orElseThrow(() -> new IllegalStateException("Multipart properties are not configured"));

        assertTrue(multipartProperties.getMaxFileSize().compareTo(DataSize.ofMegabytes(8)) > 0);
        assertTrue(multipartProperties.getMaxRequestSize().compareTo(DataSize.ofMegabytes(8)) > 0);
    }
}
