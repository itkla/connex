package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import javax.tools.ToolProvider;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Pins the production raw membership writers and founding helper callers for ownership consent. */
class OwnershipGrantArchTest {
    private static final Path ROOT = Path.of("src/main/java/ooo/klae/connex/backend");
    @Test void rawWorkspaceRoleWritersRemainWithinTheReviewedBoundaries() throws IOException {
        assertEquals(Set.of("services/WorkspaceService.java", "services/OwnershipMembershipWriter.java"), callers("workspaceMapper.updateMemberRole("));
        String source = Files.readString(ROOT.resolve("services/WorkspaceService.java"));
        assertTrue(source.contains("newRole == Role.OWNER && !\"owner\".equals(targetMembership.getRole())"));
        assertTrue(source.contains("ownershipOfferWriter.createLocked(orgId, workspaceId, actorId, targetUserId)"));
        String accepted = Files.readString(ROOT.resolve("services/OwnershipMembershipWriter.java"));
        assertTrue(accepted.contains("\"ACCEPTED\".equals(offer.getState())"));
    }
    @Test void allOtherMembershipWritersStayWithinTheirReviewedGrantPaths() throws IOException {
        assertEquals(Set.of("services/WorkspaceService.java", "services/InviteService.java", "services/InviteLinkService.java", "seeder/SeederBatchWriter.java"), callers("workspaceMapper.addMember("));
        assertEquals(Set.of("services/WorkspaceService.java"), callers("workspaceMapper.addPendingMember("));
        assertEquals(Set.of("services/WorkspaceService.java"), callers("workspaceMapper.activateMember("));
        assertEquals(Set.of("services/OrgMemberService.java", "seeder/SeederBatchWriter.java"), callers("orgMemberMapper.addMember("));
        assertEquals(Set.of("services/OrgMemberService.java", "services/OwnershipMembershipWriter.java"), callers("orgMemberMapper.addMemberClearingFounder("));
        assertEquals(Set.of(), callers("orgMemberMapper.updateRole("));
        assertEquals(Set.of("services/OrgMemberService.java"), callers("orgMemberMapper.addFoundingMember("));
        String workspace = Files.readString(ROOT.resolve("services/WorkspaceService.java"));
        assertTrue(workspace.contains("Join the workspace before accepting ownership"));
        assertTrue(workspace.contains("Ownership requires an accepted offer"));
        String organization = Files.readString(ROOT.resolve("services/OrgMemberService.java"));
        assertTrue(organization.contains("role == OrgRole.OWNER && !\"owner\".equals(currentRole)"));
        assertTrue(organization.contains("ownershipOfferWriter.createLocked(orgId, null, actorId, targetUserId)"));
    }
    @Test void foundingHelpersAreCalledOnlyByNewScopeProvisioning() throws IOException {
        assertEquals(Set.of("services/WorkspaceService.java"), callers("orgMemberService.addFoundingOwner("));
        String founding = Files.readString(ROOT.resolve("services/OrgMemberService.java"));
        assertTrue(founding.contains("hasOrganizationWorkspace(orgId)"));
    }
    @Test void rawWritersAndConsentWritersHaveOnlyTheirExactReviewedCallSites() throws IOException {
        Map<String, Set<String>> expected = Map.ofEntries(
            Map.entry("WorkspaceMapper.updateMemberRole", Set.of("WorkspaceService.changeMemberRole", "OwnershipMembershipWriter.grantAccepted")),
            Map.entry("WorkspaceMapper.addMember", Set.of("WorkspaceService.provisionWorkspace", "WorkspaceService.ensureActiveMemberInWorkspace", "InviteService.acceptResolvedInvite", "InviteLinkService.redeemResolvedLink", "SeederBatchWriter.write")),
            Map.entry("WorkspaceMapper.addPendingMember", Set.of("WorkspaceService.addPendingMemberInWorkspace")),
            Map.entry("WorkspaceMapper.activateMember", Set.of("WorkspaceService.approveMembership")),
            Map.entry("OrgMemberMapper.addMember", Set.of("OrgMemberService.applyMember", "SeederBatchWriter.write")),
            Map.entry("OrgMemberMapper.addMemberClearingFounder", Set.of("OrgMemberService.applyMember", "OwnershipMembershipWriter.grantAccepted")),
            Map.entry("OrgMemberMapper.updateRole", Set.of()),
            Map.entry("OrgMemberMapper.addFoundingMember", Set.of("OrgMemberService.addFoundingOwner")),
            Map.entry("OrgMemberService.addFoundingOwner", Set.of("WorkspaceService.orgIdForOwner")),
            Map.entry("OwnershipMembershipWriter.grantAccepted", Set.of("OwnershipOfferTransaction.accept")));
        Map<String, Set<String>> actual = new HashMap<>();
        expected.keySet().forEach(key -> actual.put(key, new TreeSet<>()));
        var compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler());
        List<Path> sourceFiles;
        try (var paths = Files.walk(ROOT)) { sourceFiles = paths.filter(p -> p.toString().endsWith(".java")).toList(); }
        try (var fileManager = compiler.getStandardFileManager(null, null, null)) {
            var task = (JavacTask) compiler.getTask(null, fileManager, null, List.of("-proc:none"), null,
                fileManager.getJavaFileObjectsFromPaths(sourceFiles));
            for (var unit : task.parse()) {
                new TreeScanner<Void, Void>() {
                    private Map<String, String> fields = new HashMap<>();
                    private String type;
                    private String method;
                    @Override public Void visitClass(ClassTree node, Void unused) {
                        var previousFields = fields; String previousType = type;
                        fields = new HashMap<>(fields); type = node.getSimpleName().toString();
                        for (var member : node.getMembers()) {
                            if (member instanceof VariableTree variable) {
                                fields.put(variable.getName().toString(), variable.getType().toString());
                            }
                        }
                        try { return super.visitClass(node, unused); }
                        finally { fields = previousFields; type = previousType; }
                    }
                    @Override public Void visitMethod(MethodTree node, Void unused) {
                        String previous = method; method = node.getName().toString();
                        try { return super.visitMethod(node, unused); }
                        finally { method = previous; }
                    }
                    @Override public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                        if (node.getMethodSelect() instanceof MemberSelectTree select) {
                            String receiver = select.getExpression().toString().replaceFirst("^this\\.", "");
                            String target = fields.get(receiver);
                            String key = target + "." + select.getIdentifier();
                            if (actual.containsKey(key)) { actual.get(key).add(type + "." + method); }
                        }
                        return super.visitMethodInvocation(node, unused);
                    }
                }.scan(unit, null);
            }
        }
        assertEquals(expected, actual, "Ownership writers gained an unreviewed production call site");
    }
    private Set<String> callers(String token) throws IOException {
        Set<String> found = new TreeSet<>();
        try (var paths = Files.walk(ROOT)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (Files.readString(path).contains(token)) { found.add(ROOT.relativize(path).toString()); }
            }
        }
        return found;
    }
}
