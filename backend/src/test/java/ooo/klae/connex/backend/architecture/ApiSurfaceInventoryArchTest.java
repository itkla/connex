package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;

import org.junit.jupiter.api.Test;
import ooo.klae.connex.backend.apisurface.ApiSurfaceInventory;

/** Approval ledger: additions, removals and authorization changes all require an explicit diff. */
class ApiSurfaceInventoryArchTest {
    @Test
    void publishedSurfaceMatchesReviewedInventory() throws Exception {
        var generator = new ApiSurfaceInventory();
        assertTrue(generator.endpoints().size() > 500, "Inventory must not pass with an empty scanner");
        assertEquals(Files.readString(ApiSurfaceInventory.INVENTORY), generator.inventory(),
            "HTTP surface changed: review the change and run generateApiSurface");
    }

    @Test
    void profileHeadRequiresAuthenticationIndependentlyOfLedgerApproval() throws Exception {
        assertEquals("authenticated", new ApiSurfaceInventory().posture("HEAD", "/api/auth/me"),
            "Implicit profile HEAD must not fall through the anonymous auth wildcard");
    }

    @Test
    void securityAndRoutingPolicyMatchesReviewedEvidence() throws Exception {
        assertEquals(Files.readString(ApiSurfaceInventory.POLICY), new ApiSurfaceInventory().policy(),
            "Authorization/routing source changed: review permitAll, domain checks and regenerate");
    }

    @Test
    void deprecatedEndpointsHaveAnOwnerMigrationAndFutureEolAndRetiredEndpointsStayAbsent() throws Exception {
        var endpoints = new ApiSurfaceInventory().endpoints();
        var rows = Files.readAllLines(Path.of("../docs/backend/api-lifecycle.tsv"));
        verifyLifecycle(endpoints, rows, LocalDate.now(ZoneOffset.UTC));
    }

    private static void verifyLifecycle(java.util.List<ApiSurfaceInventory.Endpoint> endpoints,
            java.util.List<String> rows, LocalDate today) {
        var covered = new HashSet<String>();
        assertEquals("method\tpath\tstate\towner\teol\tmigration\tissue", rows.getFirst());
        for (String row : rows.subList(1, rows.size())) {
            String[] fields = row.split("\t", -1);
            assertEquals(7, fields.length, row);
            for (String field : fields) {
                assertFalse(field.isBlank(), row);
            }
            String key = fields[0] + " " + fields[1];
            assertTrue(covered.add(key), "Duplicate lifecycle record: " + key);
            var matching = endpoints.stream().filter(e -> e.method().equals(fields[0]) && e.path().equals(fields[1])).toList();
            assertTrue(fields[2].equals("deprecated") || fields[2].equals("retired"), row);
            LocalDate eol = LocalDate.parse(fields[4]);
            if (fields[2].equals("retired")) {
                assertTrue(matching.isEmpty(), "Retired endpoint is still mapped: " + key);
            } else {
                assertFalse(matching.isEmpty(), "Stale deprecated record: " + key);
                assertTrue(matching.stream().allMatch(ApiSurfaceInventory.Endpoint::deprecated),
                    "Mark the controller method @Deprecated: " + key);
                assertTrue(eol.isAfter(today), "EOL endpoint must be removed: " + key);
            }
        }
        for (var endpoint : endpoints) {
            if (endpoint.deprecated()) {
                assertTrue(covered.contains(endpoint.method() + " " + endpoint.path()),
                    "Deprecated mapping needs lifecycle metadata: " + endpoint.line());
            }
        }
    }

    @Test
    void lifecycleRejectsExpiredMissingAndReactivatedRoutes() {
        var endpoint = new ApiSurfaceInventory.Endpoint("POST", "/api/probe", "Probe#run",
            "authenticated", "workspace", "TEST_PERMISSION", "", true);
        var endpoints = java.util.List.of(endpoint);
        String header = "method\tpath\tstate\towner\teol\tmigration\tissue";
        LocalDate today = LocalDate.of(2026, 9, 8);
        String future = "POST\t/api/probe\tdeprecated\tBackend\t2026-10-01\t/api/new\tSEC-60";
        verifyLifecycle(endpoints, java.util.List.of(header, future), today);
        assertThrows(AssertionError.class, () -> verifyLifecycle(endpoints, java.util.List.of(header), today));
        assertThrows(AssertionError.class, () -> verifyLifecycle(endpoints,
            java.util.List.of(header, future.replace("2026-10-01", "2026-09-08")), today));
        String retired = future.replace("deprecated", "retired");
        assertThrows(AssertionError.class, () -> verifyLifecycle(endpoints, java.util.List.of(header, retired), today));
        verifyLifecycle(java.util.List.of(), java.util.List.of(header, retired), today);
    }

}
