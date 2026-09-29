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

    @Test
    void lifecycleRejectsRetiredRoutesWithRenamedVariables() {
        for (String[] paths : java.util.List.of(
                new String[] {"/api/items/{id}", "/api/items/{itemId}"},
                new String[] {"/api/items/{id:[0-9]{1,8}}", "/api/items/{itemId:[0-9]{1,8}}"},
                new String[] {"/api/{group}/items/{id}", "/api/{team}/items/{itemId}"},
                new String[] {"/api/items/{*path}", "/api/items/{*rest}"})) {
            var failure = assertThrows(AssertionError.class,
                () -> verifyRetiredPath(paths[0], paths[1], "GET"), paths[1]);
            assertTrue(failure.getMessage().startsWith("Retired endpoint is still mapped:"));
        }
    }

    private static void verifyRetiredPath(String retiredPath, String activePath, String activeMethod) {
        var endpoint = new ApiSurfaceInventory.Endpoint(activeMethod, activePath, "Probe#run",
            "authenticated", "workspace", "TEST_PERMISSION", "", false);
        verifyLifecycle(java.util.List.of(endpoint), java.util.List.of(
            "method\tpath\tstate\towner\teol\tmigration\tissue",
            "GET\t" + retiredPath + "\tretired\tBackend\t2026-09-01\t/api/new\tSEC-60"),
            LocalDate.of(2026, 9, 8));
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
            String key = lifecycleKey(fields[0], fields[1]);
            assertTrue(covered.add(key), "Duplicate lifecycle record: " + key);
            var matching = endpoints.stream().filter(e -> lifecycleKey(e.method(), e.path()).equals(key)).toList();
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
                assertTrue(covered.contains(lifecycleKey(endpoint.method(), endpoint.path())),
                    "Deprecated mapping needs lifecycle metadata: " + endpoint.line());
            }
        }
    }

    /** Ignores capture names while retaining regex text and catch-all matching semantics. */
    private static String lifecycleKey(String method, String path) {
        var normalized = new StringBuilder();
        int captureDepth = 0;
        for (int index = 0; index < path.length(); index++) {
            char current = path.charAt(index);
            normalized.append(current);
            if (captureDepth > 0 && current == '\\' && index + 1 < path.length()) {
                normalized.append(path.charAt(++index));
            } else if (current == '{') {
                if (captureDepth == 0) {
                    if (index + 1 < path.length() && path.charAt(index + 1) == '*') {
                        normalized.append('*');
                        index++;
                    }
                    normalized.append('_');
                    while (index + 1 < path.length()
                            && path.charAt(index + 1) != ':' && path.charAt(index + 1) != '}') {
                        index++;
                    }
                }
                captureDepth++;
            } else if (current == '}') {
                captureDepth--;
            }
        }
        return method + " " + normalized;
    }

    @Test
    void lifecyclePreservesConstraintsLiteralsWildcardsAndMethods() {
        for (String activePath : java.util.List.of("/api/items/{itemId:[0-9]+}",
                "/api/items/{*rest}", "/api/items/**", "/api/items/*", "/api/other/{id}")) {
            verifyRetiredPath("/api/items/{id}", activePath, "GET");
        }
        verifyRetiredPath("/api/items/{id:[0-9]{1,8}}", "/api/items/{itemId:[0-9]{1,9}}", "GET");
        verifyRetiredPath("/api/items/{id}", "/api/items/{itemId}", "POST");
        assertEquals("GET /api/{_:[0-9]{1,8}}/{_:a\\{name\\}}",
            lifecycleKey("GET", "/api/{id:[0-9]{1,8}}/{value:a\\{name\\}}"));
    }

    @Test
    void lifecycleUsesNormalizedIdentityForDeprecationAndDuplicateRows() {
        var endpoint = new ApiSurfaceInventory.Endpoint("GET", "/api/items/{itemId}", "Probe#run",
            "authenticated", "workspace", "TEST_PERMISSION", "", true);
        String header = "method\tpath\tstate\towner\teol\tmigration\tissue";
        String row = "GET\t/api/items/{id}\tdeprecated\tBackend\t2026-10-01\t/api/new\tSEC-60";
        LocalDate today = LocalDate.of(2026, 9, 8);
        verifyLifecycle(java.util.List.of(endpoint), java.util.List.of(header, row), today);
        var failure = assertThrows(AssertionError.class, () -> verifyLifecycle(java.util.List.of(endpoint),
            java.util.List.of(header, row, row.replace("{id}", "{itemId}")), today));
        assertTrue(failure.getMessage().startsWith("Duplicate lifecycle record:"));
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
