package ooo.klae.connex.backend.ai;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.provider.AiInputImage;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;

class AiMediaAdmissionServiceTest {

    @Test
    void admissionBoundsGlobalAndPerOrganizationConcurrencyUntilClose() {
        AiProperties properties = properties();
        AiMediaAdmissionService service = new AiMediaAdmissionService(properties);

        AiMediaAdmissionService.Lease first = service.acquire(7, List.of(image()));
        assertThrows(TooManyRequestsException.class, () -> service.acquire(7, List.of(image())));
        AiMediaAdmissionService.Lease second = service.acquire(8, List.of(image()));
        assertThrows(TooManyRequestsException.class, () -> service.acquire(9, List.of(image())));

        first.close();
        assertDoesNotThrow(() -> service.acquire(9, List.of(image())).close());
        second.close();
    }

    @Test
    void admissionRejectsRequestsOutsideTheSharedEstimatedMemoryBudget() {
        AiProperties properties = properties();
        properties.setMaxMediaWorkingBytes(1024 * 1024);
        AiMediaAdmissionService service = new AiMediaAdmissionService(properties);

        assertThrows(TooManyRequestsException.class, () -> service.acquire(7, List.of(image())));
    }

    @Test
    void admissionEnforcesExactBudgetBelowTheOneMebibyteAccountingUnit() {
        AiProperties properties = properties();
        properties.setMaxResponseBytes(1);
        properties.setMaxMediaWorkingBytes(33);
        AiMediaAdmissionService service = new AiMediaAdmissionService(properties);

        assertThrows(TooManyRequestsException.class, () -> service.acquire(7, List.of(image())));

        properties.setMaxMediaWorkingBytes(34);
        AiMediaAdmissionService exactService = new AiMediaAdmissionService(properties);
        assertDoesNotThrow(() -> exactService.acquire(7, List.of(image())).close());
    }

    @Test
    void admissionEnforcesExactSharedBudgetAcrossOrganizations() {
        AiProperties properties = properties();
        properties.setMaxResponseBytes(1);
        properties.setMaxMediaWorkingBytes(1_100_000);
        AiMediaAdmissionService service = new AiMediaAdmissionService(properties);
        AiMediaAdmissionService.Lease first = service.acquire(7, List.of(image(75_000)));

        assertThrows(TooManyRequestsException.class,
                () -> service.acquire(8, List.of(image(75_000))));

        first.close();
        assertDoesNotThrow(() -> service.acquire(8, List.of(image(75_000))).close());
    }

    @Test
    void invalidConcurrencyConfigurationFailsStartup() {
        AiProperties properties = properties();
        properties.setMaxConcurrentMediaRequests(1);
        properties.setMaxConcurrentMediaRequestsPerOrg(2);

        assertThrows(IllegalStateException.class, () -> new AiMediaAdmissionService(properties));
    }

    private static AiProperties properties() {
        AiProperties properties = new AiProperties();
        properties.setMaxConcurrentMediaRequests(2);
        properties.setMaxConcurrentMediaRequestsPerOrg(1);
        return properties;
    }

    private static AiInputImage image() {
        return image(4);
    }

    private static AiInputImage image(int size) {
        byte[] content = new byte[size];
        content[0] = (byte) 0xff;
        content[1] = (byte) 0xd8;
        content[2] = (byte) 0xff;
        return new AiInputImage("image/jpeg", content, 100, 50);
    }
}
