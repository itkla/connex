package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ObjectStoragePropertiesTest {
    @Test
    void ambiguousS3CleanupDelayMustExceedTheTotalCallTimeout() {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setProvider(ObjectStorageProperties.Provider.S3);
        properties.getS3().setApiCallTimeout(Duration.ofSeconds(15));
        properties.setAmbiguousWriteCleanupDelayMs(15_000);

        assertFalse(properties.isAmbiguousWriteCleanupDelayValid());

        properties.setAmbiguousWriteCleanupDelayMs(15_001);

        assertTrue(properties.isAmbiguousWriteCleanupDelayValid());
    }

    @Test
    void perUserReadLimitCannotExceedTheGlobalLimit() {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setMaxConcurrentReads(2);
        properties.setMaxConcurrentReadsPerUser(3);

        assertFalse(properties.isReadConcurrencyConfigurationValid());

        properties.setMaxConcurrentReadsPerUser(2);

        assertTrue(properties.isReadConcurrencyConfigurationValid());
    }
}
