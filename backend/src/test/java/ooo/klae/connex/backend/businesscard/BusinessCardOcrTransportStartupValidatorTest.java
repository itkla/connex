package ooo.klae.connex.backend.businesscard;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class BusinessCardOcrTransportStartupValidatorTest {
    @Test
    void rejectsUnacknowledgedPlainHttpOutsideDevelopment() {
        BusinessCardProperties properties = properties("http://ocr:8090", "");

        assertThrows(IllegalStateException.class,
                () -> BusinessCardOcrTransportStartupValidator.validate(
                        properties, new MockEnvironment()));
    }

    @Test
    void rejectsRemoteOrMismatchedPlainHttpOutsideDevelopment() {
        BusinessCardProperties remote = properties("http://ocr.example.test:8090", "ocr.example.test");
        BusinessCardProperties mismatched = properties("http://ocr:8090", "other-ocr");

        assertThrows(IllegalStateException.class,
                () -> BusinessCardOcrTransportStartupValidator.validate(remote, new MockEnvironment()));
        assertThrows(IllegalStateException.class,
                () -> BusinessCardOcrTransportStartupValidator.validate(mismatched, new MockEnvironment()));
    }

    @Test
    void permitsExplicitPrivateServiceHostOutsideDevelopment() {
        BusinessCardProperties properties = properties("http://ocr:8090", "OCR");

        assertDoesNotThrow(() -> BusinessCardOcrTransportStartupValidator.validate(
                properties, new MockEnvironment()));
    }

    @Test
    void permitsPlainHttpInDevelopmentAndHttpsElsewhere() {
        BusinessCardProperties local = properties("http://127.0.0.1:8090", "");
        MockEnvironment development = new MockEnvironment();
        development.setActiveProfiles("dev");

        assertDoesNotThrow(() -> BusinessCardOcrTransportStartupValidator.validate(local, development));
        assertDoesNotThrow(() -> BusinessCardOcrTransportStartupValidator.validate(
                properties("https://ocr.example.test", ""), new MockEnvironment()));
    }

    private static BusinessCardProperties properties(String endpoint, String privateHost) {
        BusinessCardProperties properties = new BusinessCardProperties();
        properties.setOcrBaseUrl(URI.create(endpoint));
        properties.setPlainHttpPrivateHost(privateHost);
        return properties;
    }
}
