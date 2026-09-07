package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Guards, as a per-file co-occurrence grep, the ordering rule that keeps the credential paths'
 * shared organization root inert. The credential management paths take an organization root
 * {@code FOR SHARE} before the {@code workspace_member} row, while member detachment takes
 * {@code workspace_member} rows before the organization root its trailing audit acquires. That
 * inversion cannot cycle while every organization root involved is shared, so no source may take an
 * organization root EXCLUSIVELY and then lock a {@code workspace_member} row.
 *
 * <p>This grep is a rot alarm, not the proof. What actually holds the invariant is the workspace
 * root that precedes every organization root on both sides: a manager reaches an organization root
 * only through {@code WorkspaceService.lockedMemberAuthorization} or
 * {@code ApiCredentialLifecycleService.deleteForMembership}, each of which takes that workspace's
 * root ({@code lockActiveWorkspaceForShare} or {@code lockWorkspaceOrgIdForShare}) first, and
 * account erasure holds every owned workspace root exclusively before it touches an owner row. Two
 * transactions that would otherwise cross on an organization root therefore conflict on the
 * workspace root first, and whichever one waits there holds no organization root yet.
 *
 * <p>Because the check is file-scoped, a cycle spread over two sources is invisible to it. The one
 * such shape that exists — the {@code app_user} cascade at the end of account erasure X-locking
 * {@code workspace_member} rows after owned organization roots were taken exclusively — is
 * identical on {@code origin/main} and is carved out in {@code docs/backend/LOCKING.md} under
 * GitHub issue #1582 rather than by this test.
 */
class OrganizationRootLockOrderArchTest {
    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    private static final List<String> EXCLUSIVE_ORGANIZATION_ROOT_CALLS = List.of(
        "organizationMapper.lockById(",
        "organizationMapper.lockActiveById(",
        "organizationMapper.lockActiveIdentity(");

    private static final List<String> WORKSPACE_MEMBER_ROW_LOCK_CALLS = List.of(
        "workspaceMapper.lockOwnerIds(",
        "workspaceMapper.lockAuthorizationMembership(",
        "workspaceMapper.lockActiveMembership(",
        "workspaceMapper.getMembershipForUserForShare(",
        "notificationMapper.lockRecipientMemberships(",
        "userMapper.lockAssignedCustomRoleIds(");

    @Test
    void noSourceTakesAnExclusiveOrganizationRootAndAWorkspaceMemberRow() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String body = Files.readString(source, StandardCharsets.UTF_8);
                if (containsAny(body, EXCLUSIVE_ORGANIZATION_ROOT_CALLS)
                        && containsAny(body, WORKSPACE_MEMBER_ROW_LOCK_CALLS)) {
                    offenders.add(SOURCE_ROOT.relativize(source).toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
            "An exclusive organization root must never be combined with a workspace_member row "
                + "lock: the credential paths take the organization root shared before the "
                + "membership row and would then cycle against member detachment (see "
                + "docs/backend/LOCKING.md). Offending sources: " + offenders);
    }

    private static boolean containsAny(String body, List<String> needles) {
        return needles.stream().anyMatch(body::contains);
    }
}
