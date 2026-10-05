package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;

class MultipartConfigurationTest {
    private static final long GENERIC_ATTACHMENT_BYTES = 25L * 1024L * 1024L;
    private static final long IMPORT_REQUEST_BYTES = 64L * 1024L * 1024L;

    @Test
    void globalMultipartCeilingDoesNotImposeBusinessCardLimitOnOtherUploads() {
        MultipartConfigurationTestSupport.servletContextRunner().run(context -> {
            MultipartProperties multipartProperties = context.getBean(MultipartProperties.class);

            assertTrue(multipartProperties.getMaxFileSize().toBytes() >= GENERIC_ATTACHMENT_BYTES);
            assertTrue(multipartProperties.getMaxRequestSize().toBytes() >= IMPORT_REQUEST_BYTES);
        });
    }
}
