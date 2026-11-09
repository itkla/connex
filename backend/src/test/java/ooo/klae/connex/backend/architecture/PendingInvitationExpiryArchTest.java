package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Pins the serving minimum for the historical invitation expiry boundary. */
class PendingInvitationExpiryArchTest {
    @Test
    void rollbackFloorIncludesTheMembershipDeadlineCutover() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.exists(root.resolve("backend"))) {
            root = root.getParent();
        }
        assertNotNull(root);
        String floor = Files.readString(root.resolve("deploy/staging/rollback-floor")).trim();
        assertTrue(floor.matches("(?:control|tenant)/V\\d+__[a-z0-9_]+\\.sql"));
        int version = Integer.parseInt(floor.substring(floor.indexOf("/V") + 2, floor.indexOf("__")));
        assertTrue(version >= 247);
        assertTrue(Files.isRegularFile(root.resolve("backend/src/main/resources/db/migration").resolve(floor)));
        String upgrade = Files.readString(root.resolve("docs/UPGRADING.md"));
        assertTrue(upgrade.contains("Historical pending invitation expiry (control V247)"));
    }

    @Test
    void recipientListingAndAtomicClaimRequirePositiveDeadlines() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("mappers/WorkspaceMapper.xml")) {
            assertNotNull(input);
            String mapper = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            String activation = mapper.substring(mapper.indexOf("<update id=\"activateMember\">"));
            activation = activation.substring(0, activation.indexOf("</update>"));
            assertTrue(activation.contains("AND expires_at &gt; UTC_TIMESTAMP()"));
            String listing = mapper.substring(mapper.indexOf("<select id=\"getPendingMemberships\""));
            listing = listing.substring(0, listing.indexOf("</select>"));
            assertTrue(listing.contains("AND wm.expires_at &gt; UTC_TIMESTAMP()"));
        }
    }
}
