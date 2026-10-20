package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;

/** Pins reviewed scope installers and restorers without inferring transitive application behavior. */
class TenantWorkspaceScopeInstallationArchTest {
    private static final String ROOT = "ooo.klae.connex.backend.";
    private static final Set<String> MUTATORS = Set.of(
        "set", "setWithWorkspaceScopes", "setControlSelection", "swapWorkspaceScopes", "restore");
    private static final Map<String, Set<String>> REVIEWED = Map.ofEntries(
        entry("tenant.TenantContext.setWithWorkspaceScopes", "set"),
        entry("tenant.TenantResolutionInterceptor.resolveRequest", "set", "setWithWorkspaceScopes", "setControlSelection"),
        entry("publicapi.ApiCredentialAuthenticationFilter.doFilterInternal", "set", "setWithWorkspaceScopes"),
        entry("services.AutomationExecutor.runWithPinnedPlacement", "set", "restore"),
        entry("services.WorkspaceMembershipRemovalTransaction.withTenantContext", "set", "restore"),
        entry("services.UserOffboardingService.withTenantContext", "set", "restore"),
        entry("services.TenantDiagnosticsTenantAccess.withTenantContext", "set", "restore"),
        entry("services.DataSubjectDisclosureAccess.withTenantContext", "set", "restore"),
        entry("services.TenantLifecycleAccess.withTenantContext", "set", "restore"),
        entry("services.OwnershipOfferService.withIdentity", "set", "restore"),
        entry("services.OwnershipOfferWriter.withNoticeScope", "set", "restore"),
        entry("seeder.SeederBatchWriter.write", "set", "restore"),
        entry("ai.mission.AiAgentDsrPurgeService.installAcceptedIdentity", "set"),
        entry("ai.mission.AiAgentDsrPurgeService.inAcceptedScope", "restore"),
        entry("ai.mission.AiAgentDsrPurgeService.readAcceptedWork", "restore"),
        entry("tenant.TenantWorkScope.WorkspaceRouteScope.<init>", "swapWorkspaceScopes"),
        entry("tenant.TenantWorkScope.WorkspaceRouteScope.close", "swapWorkspaceScopes"),
        entry("tenant.TenantWorkScope.withPreparedWorkspace", "swapWorkspaceScopes"));

    @Test
    void productionInstallersAndRestorersMatchTheReviewedList() throws IOException {
        var compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler());
        try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
                var paths = Files.walk(Path.of("src/main/java"))) {
            var sources = manager.getJavaFileObjectsFromPaths(paths
                .filter(path -> path.toString().endsWith(".java")).sorted().toList());
            assertEquals(REVIEWED, scan(sources),
                "Every new tenant installer or restorer needs explicit route, snapshot and cleanup review");
        }
    }

    @Test
    void directUnreviewedInstallerIsRejected() throws IOException {
        assertFalse(REVIEWED.keySet().containsAll(fixture("context.set(7, 900, 42, \"member\", null);").keySet()));
    }

    @Test
    void localAliasAndMethodReferenceCannotHideAnInstaller() throws IOException {
        assertEquals(Set.of("set"), fixture("var alias = context; alias.set(7, 900, 42, \"member\", null);")
            .get("fixture.Unreviewed.install"));
        assertEquals(Set.of("set"), fixture("var writer = context::set;").get("fixture.Unreviewed.install"));
    }

    @Test
    void preparedInstallerAndRestoreNeedTheirOwnReview() throws IOException {
        assertEquals(Set.of("setWithWorkspaceScopes", "restore"), fixture("""
            context.setWithWorkspaceScopes(7, 900, 42, "member", null, null);
            context.restore(previous);
            """).get("fixture.Unreviewed.install"));
    }

    @Test
    void unrelatedSetMethodsDoNotBecomeTenantInstallers() throws IOException {
        assertTrue(fixture("Other other = new Other(); other.set(1);").isEmpty());
    }

    private static Map.Entry<String, Set<String>> entry(String installer, String... mutators) {
        return Map.entry(ROOT + installer, Set.of(mutators));
    }

    private static Map<String, Set<String>> fixture(String body) throws IOException {
        String source = """
            package fixture;
            import ooo.klae.connex.backend.tenant.TenantContext;
            class Unreviewed {
                TenantContext context;
                Object previous;
                void install() { %s }
            }
            """.formatted(body);
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///fixture/Unreviewed.java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        return scan(List.of(file));
    }

    private static Map<String, Set<String>> scan(Iterable<? extends JavaFileObject> sources) throws IOException {
        var compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler());
        try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, null, List.of("-proc:none"), null, sources);
            Map<String, Set<String>> actual = new LinkedHashMap<>();
            for (CompilationUnitTree unit : task.parse()) {
                new InstallerScanner(unit, actual).scan(unit, null);
            }
            return actual;
        }
    }

    private static final class InstallerScanner extends TreeScanner<Void, Void> {
        private final String packageName;
        private final Map<String, Set<String>> actual;
        private final Deque<String> owners = new ArrayDeque<>();
        private final Set<String> contexts = new HashSet<>();
        private String method;

        private InstallerScanner(CompilationUnitTree unit, Map<String, Set<String>> actual) {
            this.packageName = unit.getPackageName().toString();
            this.actual = actual;
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitVariable(VariableTree tree, Void ignored) {
                    String type = tree.getType() == null ? "" : tree.getType().toString();
                    if (type.equals("TenantContext") || type.endsWith(".TenantContext")) {
                        contexts.add(tree.getName().toString());
                    }
                    return super.visitVariable(tree, ignored);
                }
            }.scan(unit, null);
        }

        @Override
        public Void visitClass(ClassTree tree, Void ignored) {
            owners.addLast(tree.getSimpleName().toString());
            super.visitClass(tree, ignored);
            owners.removeLast();
            return null;
        }

        @Override
        public Void visitMethod(MethodTree tree, Void ignored) {
            String previous = method;
            method = tree.getName().toString();
            super.visitMethod(tree, ignored);
            method = previous;
            return null;
        }

        @Override
        public Void visitVariable(VariableTree tree, Void ignored) {
            String type = tree.getType() == null ? "" : tree.getType().toString();
            if (type.equals("TenantContext") || type.endsWith(".TenantContext") || isContext(tree.getInitializer())) {
                contexts.add(tree.getName().toString());
            }
            return super.visitVariable(tree, ignored);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree tree, Void ignored) {
            if (tree.getMethodSelect() instanceof MemberSelectTree select && isContext(select.getExpression())) {
                record(select.getIdentifier().toString());
            } else if (tree.getMethodSelect() instanceof IdentifierTree identifier
                    && owner().equals(ROOT + "tenant.TenantContext")) {
                record(identifier.getName().toString());
            }
            return super.visitMethodInvocation(tree, ignored);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree tree, Void ignored) {
            if (isContext(tree.getQualifierExpression())) {
                record(tree.getName().toString());
            }
            return super.visitMemberReference(tree, ignored);
        }

        private boolean isContext(Tree tree) {
            return tree instanceof IdentifierTree id && contexts.contains(id.getName().toString())
                || tree instanceof MemberSelectTree select && contexts.contains(select.getIdentifier().toString());
        }

        private void record(String mutator) {
            if (method != null && MUTATORS.contains(mutator)) {
                actual.computeIfAbsent(owner() + "." + method, key -> new HashSet<>()).add(mutator);
            }
        }

        private String owner() {
            return packageName + "." + String.join(".", owners);
        }
    }
}
