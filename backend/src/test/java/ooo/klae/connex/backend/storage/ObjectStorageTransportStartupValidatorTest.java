package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ObjectStorageTransportStartupValidatorTest {
    @Test
    void rejectsPlainHttpOutsideDevelopment() {
        ObjectStorageProperties properties = properties("http://minio.example.test:9000");

        assertThrows(IllegalStateException.class,
                () -> ObjectStorageTransportStartupValidator.validate(
                        properties, new MockEnvironment()));
    }

    @Test
    void permitsPlainHttpOnlyInDevelopment() {
        ObjectStorageProperties properties = properties("http://127.0.0.1:9000");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        assertDoesNotThrow(() -> ObjectStorageTransportStartupValidator.validate(
                properties, environment));
    }

    @Test
    void permitsHttpsOutsideDevelopment() {
        ObjectStorageProperties properties = properties("https://objects.example.test");

        assertDoesNotThrow(() -> ObjectStorageTransportStartupValidator.validate(
                properties, new MockEnvironment()));
    }

    private static ObjectStorageProperties properties(String endpoint) {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setProvider(ObjectStorageProperties.Provider.S3);
        properties.getS3().setBucket("connex");
        properties.getS3().setRegion("us-east-1");
        properties.getS3().setEndpoint(endpoint);
        return properties;
    }
}
