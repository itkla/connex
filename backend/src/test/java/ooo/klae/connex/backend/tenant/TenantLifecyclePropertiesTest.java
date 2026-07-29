package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class TenantLifecyclePropertiesTest {

    @Test
    void exportDeadlineMustStayPositiveWithoutAdmissionOrLeaseWaitSettings() {
        TenantLifecycleProperties properties = new TenantLifecycleProperties();

        assertTrue(properties.durationsValid());

        properties.setExportTimeout(Duration.ZERO);

        assertFalse(properties.durationsValid());
    }
}
