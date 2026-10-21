package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Pins the dedicated GET callback and same-connection control boundary for attachment queries. */
class AttachmentQueryReadBoundaryArchTest {
    private static final Path MAIN = Path.of("src/main/java/ooo/klae/connex/backend");

    @Test
    void libraryCallbacksUseOnlyTheirDedicatedReadInterface() throws Exception {
        String service = Files.readString(MAIN.resolve("services/AttachmentQueryReadService.java"));
        String callback = Files.readString(MAIN.resolve("services/AttachmentQueryRead.java"));
        assertTrue(service.contains("read(AttachmentQueryRead<T> read)"));
        assertTrue(callback.contains("T read();"));
        assertFalse(service.contains("Supplier"));
        assertTrue(service.contains("transaction.setReadOnly(true)"));
        assertTrue(service.contains("transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ)"));
        assertTrue(service.indexOf("requireScopePreparationAllowed(") < service.indexOf("transaction.execute("));
        assertFalse(service.contains("PROPAGATION_NOT_SUPPORTED"));
    }

    @Test
    void controlFactsUseOnlyTheReadBoundaryAndBoundedDedicatedProjection() throws Exception {
        String boundary = Files.readString(MAIN.resolve("services/AttachmentQueryControlAccess.java"));
        assertTrue(boundary.contains("tenantWorkScope.unroutedRead("));
        assertTrue(boundary.contains("USER_BATCH_SIZE = 1_000"));
        assertFalse(boundary.contains(".unrouted("));
        assertFalse(boundary.contains("getUserById("));
        assertFalse(boundary.contains("getActiveWorkspaceMemberDisplayNamesByIds("));
    }
}
