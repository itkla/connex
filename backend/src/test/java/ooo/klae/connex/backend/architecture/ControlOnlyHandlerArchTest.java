package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.RequestMapping;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;

import ooo.klae.connex.backend.tenant.ControlOnlyHandlerPolicy;
import ooo.klae.connex.backend.dto.MyWorkspacesDto;
import ooo.klae.connex.backend.dto.UserDto;

/**
 * A pinned-handler and direct-dependency tripwire, including simple aliases and method references.
 * Selected dependency entries and their source-local helpers also reject event publication and
 * async submission. This is not a transitive containment proof: the runtime SQL guard, inherited
 * origin and executor task decoration enforce descendants' restrictions independently of this scan.
 */
class ControlOnlyHandlerArchTest {
    private static final Path ROOT = Path.of("src/main/java/ooo/klae/connex/backend");
    private static final Map<String, String> MAPPINGS = Map.of(
        "WorkspaceController#myWorkspaces()", "GET /api/workspaces",
        "WorkspaceController#pending()", "GET /api/workspaces/pending",
        "WorkspaceController#switchWorkspace(int,HttpServletResponse)", "POST /api/workspaces/{id}/switch",
        "UserController#getUserById(int)", "GET /api/users/{id}",
        "UserController#updateUser(int,UserDto)", "PUT /api/users/{id}",
        "UserController#updateCurrentUserLocale(UpdateLocaleDto)", "PATCH /api/users/me/locale");
    private static final Map<String, Set<String>> DEPENDENCIES = Map.ofEntries(
        Map.entry("WorkspaceController#myWorkspaces", Set.of(
            "authService.getCurrentUser", "workspaceService.getMembershipsForCurrentUser",
            "workspaceService.defaultWorkspaceIdFor")),
        Map.entry("WorkspaceController#pending", Set.of(
            "authService.getCurrentUser", "workspaceService.pendingMemberships")),
        Map.entry("WorkspaceController#switchWorkspace", Set.of(
            "authService.getCurrentUser", "workspaceService.requireMember",
            "workspaceService.rememberActive", "workspaceCookie.set")),
        Map.entry("UserController#getUserById", Set.of("userService.getUserById")),
        Map.entry("UserController#updateUser", Set.of("userService.update")),
        Map.entry("UserController#updateCurrentUserLocale", Set.of(
            "authService.getCurrentUser", "userService.updateLocale")),
        Map.entry("UserService#getUserById", Set.of("this.getActiveWorkspaceUser",
            "workspaceService.getCurrentWorkspaceId")),
        Map.entry("UserService#getActiveWorkspaceUser", Set.of("workspaceService.isMember", "userMapper.getUserById")),
        Map.entry("UserService#update", Set.of("workspaceService.requireSelf", "this.getUserById",
            "userMapper.update", "userMapper.getUserById", "accountSessionRevocationService.closeWebSocketsAfterRename",
            "auditService.record", "auditService.diff")),
        Map.entry("UserService#updateLocale", Set.of("workspaceService.requireSelf", "this.getUserById",
            "userMapper.updateLocale", "auditService.record", "auditService.singleChange", "userMapper.getUserById")),
        Map.entry("WorkspaceService#getMembershipsForCurrentUser", Set.of("workspaceMapper.getMembershipsForUser", "this.currentUser")),
        Map.entry("WorkspaceService#pendingMemberships", Set.of("workspaceMapper.getPendingMemberships")),
        Map.entry("WorkspaceService#defaultWorkspaceIdFor", Set.of("workspaceMapper.getLastActiveWorkspaceId",
            "workspaceMapper.isMember", "this.firstMembershipWorkspaceIdFor")),
        Map.entry("WorkspaceService#firstMembershipWorkspaceIdFor", Set.of("workspaceMapper.getWorkspacesForUser")),
        Map.entry("WorkspaceService#requireMember", Set.of("this.isMember")),
        Map.entry("WorkspaceService#isMember", Set.of("workspaceMapper.isMember")),
        Map.entry("WorkspaceService#rememberActive", Set.of("workspaceMapper.setLastActiveWorkspaceId")));
    private static final Map<String, Set<String>> DEPENDENCY_ENTRIES = Map.of(
        "services/AuthService.java", Set.of("getCurrentUser"),
        "services/AccountSessionRevocationService.java", Set.of("closeWebSocketsAfterRename"),
        "notifications/WebSocketSessionRegistry.java", Set.of("closeByUser"),
        "services/AuditService.java", Set.of("record", "diff", "singleChange"),
        "tenant/WorkspaceCookie.java", Set.of("set"));
    private static final Set<String> DELEGATION_METHODS = Set.of(
        "publish", "publishEvent", "execute", "submit", "submitCompletable", "start", "startVirtualThread",
        "parallel", "parallelStream");

    @Test
    void onlyTheExactReviewedSignaturesAndMappingsCanDeferPlacement() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (var entry : ControlOnlyHandlerPolicy.handlers().entrySet()) {
            Method method = entry.getKey();
            assertTrue(Set.of(MyWorkspacesDto.class, UserDto.class, List.class, void.class)
                .contains(method.getReturnType()), "Control-only handlers must complete synchronously");
            assertTrue(!AnnotatedElementUtils.hasAnnotation(method, Async.class)
                && !AnnotatedElementUtils.hasAnnotation(method.getDeclaringClass(), Async.class));
            RequestMapping controller = Objects.requireNonNull(AnnotatedElementUtils.findMergedAnnotation(
                method.getDeclaringClass(), RequestMapping.class));
            RequestMapping mapping = Objects.requireNonNull(AnnotatedElementUtils.findMergedAnnotation(
                method, RequestMapping.class));
            assertEquals(1, controller.path().length);
            assertEquals(1, mapping.method().length);
            assertEquals(entry.getValue(), mapping.method()[0].name());
            assertTrue(mapping.path().length <= 1);
            String path = controller.path()[0] + (mapping.path().length == 0 ? "" : mapping.path()[0]);
            String parameters = java.util.Arrays.stream(method.getParameterTypes())
                .map(Class::getSimpleName).collect(Collectors.joining(","));
            actual.put(method.getDeclaringClass().getSimpleName() + "#" + method.getName()
                + "(" + parameters + ")", entry.getValue() + " " + path);
        }
        assertEquals(MAPPINGS, actual);
    }

    @Test
    void reviewedHandlersAndServiceEntriesKeepTheirExactDirectDependencies() throws IOException {
        Map<String, Set<String>> actual = new HashMap<>();
        for (String source : List.of("controllers/WorkspaceController.java", "controllers/UserController.java",
                "services/WorkspaceService.java", "services/UserService.java")) {
            scan(List.of(new SourceFile(ROOT.resolve(source))), actual);
        }
        assertEquals(DEPENDENCIES, actual,
            "Control-only paths gained an unreviewed dependency; inspect its plane and exercise the control-only catalog");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "tenantMapper.read();",
        "helper.readTenant();",
        "events.publishEvent(new Object());",
        "callbacks.run(() -> tenantMapper.read());",
        "var alias = tenantMapper; alias.read();",
        "var alias = this.tenantMapper; var second = alias; second.read();",
        "Object read = tenantMapper::read;",
        "var alias = tenantMapper; Object read = alias::read;"
    })
    void dependencyContractRejectsDirectHelperEventAndCallbackTenantWork(String body) throws IOException {
        String source = "class UserController { Object userService; Object tenantMapper; Object helper; "
            + "Object events; Object callbacks; void getUserById() { userService.getUserById(); " + body + " } }";
        Map<String, Set<String>> actual = new HashMap<>();
        scan(List.of(new SourceText(source)), actual);

        assertThrows(AssertionError.class, () -> assertEquals(
            DEPENDENCIES.get("UserController#getUserById"), actual.get("UserController#getUserById")));
    }

    @Test
    void acceptedDependencyEntriesAndTheirLocalHelpersCannotDelegateWork() throws IOException {
        for (var entry : DEPENDENCY_ENTRIES.entrySet()) {
            assertNoDelegation(new SourceFile(ROOT.resolve(entry.getKey())), entry.getValue());
        }
        for (String source : List.of("controllers/WorkspaceController.java", "controllers/UserController.java",
                "services/WorkspaceService.java", "services/UserService.java")) {
            String type = Path.of(source).getFileName().toString().replace(".java", "");
            Set<String> entries = DEPENDENCIES.keySet().stream().filter(key -> key.startsWith(type + "#"))
                .map(key -> key.substring(key.indexOf('#') + 1)).collect(Collectors.toSet());
            assertNoDelegation(new SourceFile(ROOT.resolve(source)), entries);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "events.publishEvent(new Object());",
        "var alias = events; alias.publishEvent(new Object());",
        "Object callback = events::publishEvent;",
        "executor.execute(() -> tenantMapper.read());",
        "java.util.concurrent.CompletableFuture.runAsync(() -> tenantMapper.read());"
    })
    void acceptedDependencyHelperCannotHideEventOrAsyncSubmission(String body) throws IOException {
        String source = "class AccountSessionRevocationService { Object events; Object executor; Object tenantMapper; "
            + "void closeWebSocketsAfterRename() { helper(); } void helper() { " + body + " } }";

        assertThrows(AssertionError.class, () -> assertNoDelegation(new SourceText(source),
            Set.of("closeWebSocketsAfterRename")));
    }

    private static void assertNoDelegation(JavaFileObject file, Set<String> entries) throws IOException {
        var compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler());
        try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, null,
                List.of("-proc:none"), null, List.of(file));
            Map<String, List<MethodTree>> methods = new HashMap<>();
            for (var unit : task.parse()) {
                for (var declaration : unit.getTypeDecls()) {
                    if (declaration instanceof ClassTree type) {
                        assertTrue(type.getModifiers().getAnnotations().stream().noneMatch(annotation ->
                            annotation.getAnnotationType().toString().matches("(?:.*\\.)?Async")), file.getName());
                        for (var member : type.getMembers()) {
                            if (member instanceof MethodTree method) {
                                methods.computeIfAbsent(method.getName().toString(),
                                    ignored -> new java.util.ArrayList<>()).add(method);
                            }
                        }
                    }
                }
            }
            var pending = new ArrayDeque<>(entries);
            Set<String> seen = new HashSet<>();
            while (!pending.isEmpty()) {
                String name = pending.removeFirst();
                if (!seen.add(name)) {
                    continue;
                }
                for (MethodTree method : Objects.requireNonNull(methods.get(name), "Missing reviewed entry " + name)) {
                    assertTrue(method.getModifiers().getAnnotations().stream().noneMatch(annotation ->
                        annotation.getAnnotationType().toString().matches("(?:.*\\.)?Async")), name);
                    new TreeScanner<Void, Void>() {
                        @Override
                        public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                            String invoked = call.getMethodSelect() instanceof MemberSelectTree select
                                ? select.getIdentifier().toString() : call.getMethodSelect().toString();
                            refuseDelegation(invoked);
                            String receiver = call.getMethodSelect() instanceof MemberSelectTree select
                                ? select.getExpression().toString() : "this";
                            if (receiver.equals("this") && methods.containsKey(invoked)) {
                                pending.add(invoked);
                            }
                            return super.visitMethodInvocation(call, unused);
                        }

                        @Override
                        public Void visitMemberReference(MemberReferenceTree reference, Void unused) {
                            String invoked = reference.getName().toString();
                            refuseDelegation(invoked);
                            if (reference.getQualifierExpression().toString().equals("this")
                                    && methods.containsKey(invoked)) {
                                pending.add(invoked);
                            }
                            return super.visitMemberReference(reference, unused);
                        }

                        private void refuseDelegation(String invoked) {
                            assertTrue(!DELEGATION_METHODS.contains(invoked) && !invoked.endsWith("Async"),
                                file.getName() + "#" + name + " delegates work through " + invoked);
                        }
                    }.scan(method.getBody(), null);
                }
            }
        }
    }

    private static void scan(List<? extends JavaFileObject> files, Map<String, Set<String>> calls)
            throws IOException {
        var compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler());
        try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, null,
                List.of("-proc:none"), null, files);
            for (var unit : task.parse()) {
                new TreeScanner<Void, Void>() {
                    private Set<String> fields = Set.of();
                    private String type;
                    private Set<String> current;
                    private Map<String, String> aliases = new HashMap<>();

                    @Override
                    public Void visitClass(ClassTree tree, Void unused) {
                        Set<String> previousFields = fields;
                        String previousType = type;
                        type = tree.getSimpleName().toString();
                        fields = tree.getMembers().stream().filter(VariableTree.class::isInstance)
                            .map(VariableTree.class::cast).map(field -> field.getName().toString())
                            .collect(Collectors.toSet());
                        try {
                            return super.visitClass(tree, unused);
                        } finally {
                            fields = previousFields;
                            type = previousType;
                        }
                    }

                    @Override
                    public Void visitMethod(MethodTree tree, Void unused) {
                        Set<String> previous = current;
                        Map<String, String> previousAliases = aliases;
                        aliases = new HashMap<>();
                        String key = type + "#" + tree.getName();
                        current = DEPENDENCIES.containsKey(key)
                            ? calls.computeIfAbsent(key, ignored -> new TreeSet<>()) : null;
                        try {
                            return super.visitMethod(tree, unused);
                        } finally {
                            current = previous;
                            aliases = previousAliases;
                        }
                    }

                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
                        if (current != null) {
                            if (tree.getMethodSelect() instanceof IdentifierTree identifier) {
                                current.add("this." + identifier.getName());
                            } else if (tree.getMethodSelect() instanceof MemberSelectTree select) {
                                String receiver = dependencyReceiver(select.getExpression().toString());
                                if (receiver != null) {
                                    current.add(receiver + "." + select.getIdentifier());
                                }
                            }
                        }
                        return super.visitMethodInvocation(tree, unused);
                    }

                    @Override
                    public Void visitMemberReference(MemberReferenceTree reference, Void unused) {
                        if (current != null) {
                            String receiver = dependencyReceiver(reference.getQualifierExpression().toString());
                            if (receiver != null) {
                                current.add(receiver + "." + reference.getName());
                            }
                        }
                        return super.visitMemberReference(reference, unused);
                    }

                    @Override
                    public Void visitVariable(VariableTree variable, Void unused) {
                        if (current != null && variable.getInitializer() != null) {
                            recordAlias(variable.getName().toString(), variable.getInitializer().toString());
                        }
                        return super.visitVariable(variable, unused);
                    }

                    @Override
                    public Void visitAssignment(AssignmentTree assignment, Void unused) {
                        if (current != null) {
                            recordAlias(assignment.getVariable().toString(), assignment.getExpression().toString());
                        }
                        return super.visitAssignment(assignment, unused);
                    }

                    private void recordAlias(String alias, String expression) {
                        String receiver = dependencyReceiver(expression);
                        if (receiver == null) {
                            aliases.remove(alias);
                        } else {
                            aliases.put(alias, receiver);
                        }
                    }

                    private String dependencyReceiver(String expression) {
                        String receiver = expression.replaceFirst("^this\\.", "");
                        return aliases.getOrDefault(receiver, fields.contains(receiver) ? receiver : null);
                    }
                }.scan(unit, null);
            }
        }
    }

    private static final class SourceFile extends SimpleJavaFileObject {
        private final Path path;

        private SourceFile(Path path) {
            super(path.toUri(), Kind.SOURCE);
            this.path = path;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
            return java.nio.file.Files.readString(path);
        }
    }

    private static final class SourceText extends SimpleJavaFileObject {
        private final String source;

        private SourceText(String source) {
            super(URI.create("string:///UserController.java"), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
