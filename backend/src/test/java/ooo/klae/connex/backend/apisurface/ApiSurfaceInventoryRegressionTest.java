package ooo.klae.connex.backend.apisurface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regressions for source approval and required endpoint deprecation metadata. */
class ApiSurfaceInventoryRegressionTest {
    @TempDir
    Path temporary;

    @Test
    void removingDomainAuthorizationChangesPolicyAndFailsApproval() throws Exception {
        Path sources = Path.of("src/main/java");
        try (var paths = Files.walk(sources)) {
            for (Path source : paths.filter(Files::isRegularFile).toList()) {
                Path copy = temporary.resolve(sources.relativize(source));
                Files.createDirectories(copy.getParent());
                Files.copy(source, copy);
            }
        }
        var generator = new ApiSurfaceInventory();
        String approved = generator.policy();
        assertEquals(approved, generator.policy(temporary));
        Path service = temporary.resolve("ooo/klae/connex/backend/ai/AiGenerationService.java");
        String original = Files.readString(service);
        String check = "workspaceService.permissionsFor(workspaceId, userId)\n"
            + "                .containsAll(state.requiredPermissions)";
        assertTrue(original.contains(check), "Mutation must remove the status authorization check");
        Files.writeString(service, original.replace(check, "true"));
        String mutated = generator.policy(temporary);
        assertNotEquals(approved, mutated, "Domain authorization removal must change the policy digest");
        assertThrows(AssertionError.class, () -> assertEquals(approved, mutated,
            "Authorization/routing source changed: review permitAll, domain checks and regenerate"));
    }

    @Test
    void policyIncludesNamedDomainBoundariesAndSourceFloor() throws Exception {
        String policy = new ApiSurfaceInventory().policy();
        assertTrue(policy.lines().filter(line -> line.startsWith("src/main/java/")).count() >= 500,
            "Policy source coverage must not silently shrink");
        for (String source : java.util.List.of("ai/AiGenerationService.java",
                "publicapi/ApiCredentialService.java", "connectedaccounts/ProviderConnectionService.java")) {
            assertTrue(policy.contains("/" + source + "\t"), source);
        }
    }

    @Test
    void bareDeprecationIsRejected() {
        assertInvalid(Bare.class);
    }

    @Test
    void deprecationWithoutRemovalIntentIsRejected() {
        assertInvalid(NoRemoval.class);
    }

    @Test
    void invalidDeprecationDateIsRejected() {
        assertInvalid(InvalidDate.class);
    }

    @Test
    void controllerDeprecationIsValidatedEvenWithValidMethodMetadata() {
        assertInvalid(InvalidController.class);
    }

    @Test
    void validAndActiveDeprecationMetadataIsAccepted() throws Exception {
        assertTrue(ApiSurfaceInventory.deprecated(Valid.class, Valid.class.getDeclaredMethod("endpoint")));
        assertTrue(ApiSurfaceInventory.deprecated(ValidController.class,
            ValidController.class.getDeclaredMethod("endpoint")));
        assertFalse(ApiSurfaceInventory.deprecated(Active.class, Active.class.getDeclaredMethod("endpoint")));
    }

    private static void assertInvalid(Class<?> controller) {
        var failure = assertThrows(IllegalStateException.class,
            () -> ApiSurfaceInventory.deprecated(controller, controller.getDeclaredMethod("endpoint")));
        assertTrue(failure.getMessage().contains("@Deprecated(since = \"YYYY-MM-DD\", forRemoval = true)"));
        assertTrue(failure.getMessage().contains(controller.getName()));
    }

    private static class Bare {
        @Deprecated
        void endpoint() { }
    }

    private static class NoRemoval {
        @Deprecated(since = "2026-09-08", forRemoval = false)
        void endpoint() { }
    }

    private static class InvalidDate {
        @Deprecated(since = "2026-02-30", forRemoval = true)
        void endpoint() { }
    }

    @Deprecated
    private static class InvalidController {
        @Deprecated(since = "2026-09-08", forRemoval = true)
        void endpoint() { }
    }

    private static class Valid {
        @Deprecated(since = "2026-09-08", forRemoval = true)
        void endpoint() { }
    }

    @Deprecated(since = "2026-09-08", forRemoval = true)
    private static class ValidController {
        void endpoint() { }
    }

    private static class Active {
        void endpoint() { }
    }
}
