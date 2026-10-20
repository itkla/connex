package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.File;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
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
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

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

    private static final Map<String, String> PREPARATION_ENTRIES = Map.ofEntries(
        Map.entry("tenant.TenantResolutionInterceptor.resolveRequest", "tenant.TenantResolutionInterceptor.resolveRequest"),
        Map.entry("publicapi.ApiCredentialAuthenticationFilter.doFilterInternal", "publicapi.ApiCredentialAuthenticationFilter.doFilterInternal"),
        Map.entry("services.AutomationExecutor.runWithPinnedPlacement", "services.AutomationExecutor.runAs"),
        Map.entry("services.WorkspaceMembershipRemovalTransaction.withTenantContext", "services.WorkspaceMembershipRemovalTransaction.execute"),
        Map.entry("services.UserOffboardingService.withTenantContext", "services.UserOffboardingService.prepareFreshMembership"),
        Map.entry("services.TenantDiagnosticsTenantAccess.withTenantContext", "services.TenantDiagnosticsTenantAccess.inWorkspace"),
        Map.entry("services.DataSubjectDisclosureAccess.withTenantContext", "services.DataSubjectDisclosureAccess.inSubjectWorkspace"),
        Map.entry("services.TenantLifecycleAccess.withTenantContext", "services.TenantLifecycleAccess.withRoute"),
        Map.entry("services.OwnershipOfferService.withIdentity", "services.OwnershipOfferService.routed"),
        Map.entry("tenant.TenantWorkScope.WorkspaceRouteScope.<init>", "tenant.TenantWorkScope.routeFor"),
        Map.entry("tenant.TenantWorkScope.withPreparedWorkspace", "tenant.TenantWorkScope.withPreparedWorkspace"));
    private static final Map<String, String> EXCEPTIONS = Map.ofEntries(
        Map.entry("tenant.TenantContext.setWithWorkspaceScopes", "Binds caller-prepared data; never performs reads"),
        Map.entry("services.OwnershipOfferWriter.withNoticeScope", "Identity-only under existing locks; incompatible payload is discarded"),
        Map.entry("seeder.SeederBatchWriter.write", "In-transaction roots are uncommitted; direct mapper writes need no scope"),
        Map.entry("ai.mission.AiAgentDsrPurgeService.installAcceptedIdentity", "Identity-only accepted erasure authority; no visibility-scope reads"),
        Map.entry("ai.mission.AiAgentDsrPurgeService.inAcceptedScope", "Restores complete enclosing state"),
        Map.entry("ai.mission.AiAgentDsrPurgeService.readAcceptedWork", "Restores complete enclosing state"),
        Map.entry("tenant.TenantWorkScope.WorkspaceRouteScope.close", "Restores the enclosing route payload"));
    private static final Map<String, Set<String>> PREPARATION_CALLS = Map.ofEntries(
        Map.entry("tenant.TenantResolutionInterceptor.resolveRequest", Set.of("prepareWorkspaceScopes")),
        Map.entry("publicapi.ApiCredentialAuthenticationFilter.doFilterInternal", Set.of("prepareWorkspaceScopes")),
        Map.entry("services.AutomationExecutor.runAs", Set.of("withWorkspacePlacement")),
        Map.entry("services.AutomationExecutor.runAsObserver", Set.of("withWorkspacePlacement")),
        Map.entry("services.WorkspaceMembershipRemovalTransaction.execute", Set.of("withWorkspacePlacement")),
        Map.entry("services.UserOffboardingService.prepareFreshMembership", Set.of("withWorkspacePlacement")),
        Map.entry("services.TenantDiagnosticsTenantAccess.inWorkspace", Set.of("withWorkspacePlacement")),
        Map.entry("services.TenantDiagnosticsTenantAccess.inOrganization", Set.of("withWorkspacePlacement")),
        Map.entry("services.DataSubjectDisclosureAccess.inSubjectWorkspace", Set.of("withWorkspacePlacement")),
        Map.entry("services.DataSubjectDisclosureAccess.withLockedSubjectPerson", Set.of("withWorkspacePlacement")),
        Map.entry("services.DataSubjectDisclosureAccess.withSnapshot", Set.of("withPreparedWorkspace")),
        Map.entry("services.TenantLifecycleAccess.withRoute", Set.of("withPreparedWorkspace")),
        Map.entry("services.TenantLifecycleAccess.capture", Set.of("prepareLifecycleWorkspace")),
        Map.entry("services.OwnershipOfferService.routed", Set.of("withWorkspacePlacement", "withPreparedWorkspace")),
        Map.entry("services.FreshMembershipTransaction.execute", Set.of("inWorkspace")),
        Map.entry("services.FreshMembershipTransaction.executeWithPreparedScopes", Set.of("inWorkspace")),
        Map.entry("tenant.TenantWorkScope.routeFor", Set.of("prepareWorkspaceScopes", "forWorkspace")),
        Map.entry("tenant.TenantWorkScope.withPreparedWorkspace", Set.of("prepareWorkspaceScopes")),
        Map.entry("tenant.TenantWorkScope.prepareWorkspaceScopes", Set.of("load", "isActualTransactionActive")),
        Map.entry("tenant.TenantWorkScope.prepareLifecycleWorkspace", Set.of("forLifecycleWorkspace", "isActualTransactionActive")));

    /** Pins reviewed call edges; mode and transaction ordering are exercised by the entry unit tests. */
    @Test
    void everyInstallerHasAPreparationEntryOrAnExplicitExceptionInBothModes() throws IOException {
        Set<String> classified = new HashSet<>(PREPARATION_ENTRIES.keySet());
        assertTrue(java.util.Collections.disjoint(classified, EXCEPTIONS.keySet()));
        classified.addAll(EXCEPTIONS.keySet());
        assertEquals(REVIEWED.keySet(), classified.stream().map(name -> ROOT + name).collect(Collectors.toSet()));
        assertTrue(PREPARATION_CALLS.keySet().containsAll(PREPARATION_ENTRIES.values()));
        var calls = preparationCalls();
        for (var expected : PREPARATION_CALLS.entrySet()) {
            assertTrue(calls.getOrDefault(expected.getKey(), Set.of()).containsAll(expected.getValue()),
                () -> "Missing reviewed preparation path: " + expected);
        }
        for (String entry : List.of("services.FreshMembershipTransaction.executeWithPreparedScopes",
                "services.TenantLifecycleAccess.capture", "tenant.TenantWorkScope.prepareWorkspaceScopes",
                "tenant.TenantWorkScope.prepareLifecycleWorkspace")) {
            assertFalse(calls.get(entry).contains("isCatalogPerPlacement"),
                () -> "Preparation must not be gated on routed mode: " + entry);
        }
    }

    private static Map<String, Set<String>> preparationCalls() throws IOException {
        var compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler());
        Set<Path> paths = PREPARATION_CALLS.keySet().stream().map(name -> name.substring(0, name.lastIndexOf('.')))
            .map(name -> Path.of("src/main/java", ROOT.replace('.', '/') + name.replace('.', '/') + ".java"))
            .collect(Collectors.toSet());
        try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            JavacTask parser = (JavacTask) compiler.getTask(null, manager, null, List.of("-proc:none"),
                null, manager.getJavaFileObjectsFromPaths(paths));
            Map<String, Set<String>> calls = new LinkedHashMap<>();
            for (var unit : parser.parse()) {
                new TreeScanner<Void, String>() {
                    private String owner;

                    @Override
                    public Void visitClass(ClassTree tree, String ignored) {
                        String previous = owner;
                        owner = unit.getPackageName().toString().substring(ROOT.length()) + "." + tree.getSimpleName();
                        super.visitClass(tree, ignored);
                        owner = previous;
                        return null;
                    }

                    @Override
                    public Void visitMethod(MethodTree tree, String ignored) {
                        return super.visitMethod(tree, owner + "." + tree.getName());
                    }

                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree tree, String method) {
                        if (method != null) {
                            calls.computeIfAbsent(method, key -> new HashSet<>()).add(methodName(tree));
                        }
                        return super.visitMethodInvocation(tree, method);
                    }
                }.scan(unit, null);
            }
            return calls;
        }
    }

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
        assertEquals(Set.of("set"), fixture("Installer writer = context::set;").get("fixture.Unreviewed.install"));
    }

    @Test
    void providerReceiverCannotHideAnInstaller() throws IOException {
        assertEquals(Set.of("set"), fixture("provider.getObject().set(7, 900, 42, \"member\", null);")
            .get("fixture.Unreviewed.install"));
    }

    @Test
    void constructedReceiverCannotHideAnInstaller() throws IOException {
        assertEquals(Set.of("set"), fixture("new TenantContext().set(7, 900, 42, \"member\", null);")
            .get("fixture.Unreviewed.install"));
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
            import org.springframework.beans.factory.ObjectProvider;
            class Unreviewed {
                TenantContext context;
                ObjectProvider<TenantContext> provider;
                TenantContext.State previous;
                interface Installer {
                    void install(int workspaceId, int orgId, int userId, String role, String catalog);
                }
                static class Other {
                    void set(int value) {}
                }
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
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            JavacTask parser = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null, sources);
            List<JavaFileObject> candidates = new ArrayList<>();
            for (CompilationUnitTree unit : parser.parse()) {
                var calls = new TreeScanner<Boolean, Void>() {
                    @Override
                    public Boolean visitMethodInvocation(MethodInvocationTree tree, Void ignored) {
                        return MUTATORS.contains(methodName(tree)) || Boolean.TRUE.equals(super.visitMethodInvocation(tree, ignored));
                    }

                    @Override
                    public Boolean visitMemberReference(MemberReferenceTree tree, Void ignored) {
                        return MUTATORS.contains(tree.getName().toString()) || Boolean.TRUE.equals(super.visitMemberReference(tree, ignored));
                    }

                    @Override
                    public Boolean reduce(Boolean first, Boolean second) {
                        return Boolean.TRUE.equals(first) || Boolean.TRUE.equals(second);
                    }
                };
                if (Boolean.TRUE.equals(calls.scan(unit, null))) {
                    candidates.add(unit.getSourceFile());
                }
            }
            assertTrue(diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR),
                () -> "Cannot parse scope installers: " + diagnostics.getDiagnostics());
            Map<String, Set<String>> actual = new LinkedHashMap<>();
            if (candidates.isEmpty()) {
                return actual;
            }
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                List.of("-proc:none", "-implicit:none", "-Xmaxerrs", "10000", "-classpath", runtimeClasspath()), null, candidates);
            List<CompilationUnitTree> units = new ArrayList<>();
            for (CompilationUnitTree unit : task.parse()) {
                units.add(unit);
            }
            task.analyze();
            Trees trees = Trees.instance(task);
            TypeElement context = Objects.requireNonNull(task.getElements().getTypeElement(ROOT + "tenant.TenantContext"));
            for (CompilationUnitTree unit : units) {
                new InstallerScanner(unit, actual, trees, task.getTypes(), context.asType()).scan(unit, null);
            }
            return actual;
        }
    }

    private static String runtimeClasspath() {
        List<String> entries = new ArrayList<>();
        entries.add(System.getProperty("java.class.path"));
        for (ClassLoader loader = TenantWorkspaceScopeInstallationArchTest.class.getClassLoader();
                loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (var url : urls.getURLs()) {
                    if (url.getProtocol().equals("file")) {
                        entries.add(Path.of(URI.create(url.toString())).toString());
                    }
                }
            }
        }
        return entries.stream().distinct().collect(Collectors.joining(File.pathSeparator));
    }

    private static String methodName(MethodInvocationTree tree) {
        return tree.getMethodSelect() instanceof MemberSelectTree select ? select.getIdentifier().toString()
            : ((IdentifierTree) tree.getMethodSelect()).getName().toString();
    }

    /** Attributes candidate receivers against compiled dependencies; this does not infer call chains. */
    private static final class InstallerScanner extends TreePathScanner<Void, Void> {
        private final String packageName;
        private final Map<String, Set<String>> actual;
        private final Deque<String> owners = new ArrayDeque<>();
        private final Trees trees;
        private final Types types;
        private final TypeMirror contextType;
        private String method;

        private InstallerScanner(CompilationUnitTree unit, Map<String, Set<String>> actual,
                Trees trees, Types types, TypeMirror contextType) {
            this.packageName = unit.getPackageName().toString();
            this.actual = actual;
            this.trees = trees;
            this.types = types;
            this.contextType = contextType;
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
        public Void visitMethodInvocation(MethodInvocationTree tree, Void ignored) {
            String name = methodName(tree);
            if (MUTATORS.contains(name)) {
                if (tree.getMethodSelect() instanceof MemberSelectTree select) {
                    var selected = new TreePath(getCurrentPath(), select);
                    if (isContext(new TreePath(selected, select.getExpression()))) {
                        record(name);
                    }
                } else if (owner().equals(ROOT + "tenant.TenantContext")) {
                    record(name);
                }
            }
            return super.visitMethodInvocation(tree, ignored);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree tree, Void ignored) {
            if (MUTATORS.contains(tree.getName().toString())
                    && isContext(new TreePath(getCurrentPath(), tree.getQualifierExpression()))) {
                record(tree.getName().toString());
            }
            return super.visitMemberReference(tree, ignored);
        }

        private boolean isContext(TreePath receiver) {
            TypeMirror type = trees.getTypeMirror(receiver);
            assertTrue(type != null && type.getKind() != TypeKind.ERROR,
                () -> "Cannot resolve installer receiver type in " + owner() + ": " + receiver.getLeaf());
            return types.isSubtype(types.erasure(type), types.erasure(contextType));
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
