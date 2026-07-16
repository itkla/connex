package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ImageDecodeAdmissionServiceTest {
    @Test
    void sharesDecodeSlotsAndWorkingMemoryAcrossCallers() {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setMaxConcurrentImageDecodes(2);
        properties.setMaxImageWorkingBytes(2L * 1024L * 1024L);
        ImageDecodeAdmissionService admission = new ImageDecodeAdmissionService(properties);

        try (ImageDecodeAdmissionService.Lease first = admission.tryAcquire().orElseThrow();
                ImageDecodeAdmissionService.Lease second = admission.tryAcquire().orElseThrow()) {
            assertTrue(first.tryReserve(2L * 1024L * 1024L));
            assertFalse(second.tryReserve(1));
            assertTrue(admission.tryAcquire().isEmpty());
        }

        try (ImageDecodeAdmissionService.Lease recovered = admission.tryAcquire().orElseThrow()) {
            assertTrue(recovered.tryReserve(2L * 1024L * 1024L));
        }
    }
}
