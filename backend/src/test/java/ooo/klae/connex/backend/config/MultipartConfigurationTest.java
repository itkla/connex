package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class MultipartConfigurationTest {
    private static final long GENERIC_ATTACHMENT_BYTES = 25L * 1024L * 1024L;
    private static final long IMPORT_REQUEST_BYTES = 64L * 1024L * 1024L;

    @Autowired private MultipartProperties multipartProperties;

    @Test
    void globalMultipartCeilingDoesNotImposeBusinessCardLimitOnOtherUploads() {
        assertTrue(multipartProperties.getMaxFileSize().toBytes() >= GENERIC_ATTACHMENT_BYTES);
        assertTrue(multipartProperties.getMaxRequestSize().toBytes() >= IMPORT_REQUEST_BYTES);
    }
}
