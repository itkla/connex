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
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;

import ooo.klae.connex.backend.tenant.ControlOnlyHandlerPolicy;
import ooo.klae.connex.backend.dto.MyWorkspacesDto;
import ooo.klae.connex.backend.dto.UserDto;

/**
 * Pins the reviewed handlers and their direct application dependencies. Real HTTP integration tests
 * run their production service paths against a catalog without tenant tables; this is deliberately
 * not a general transitive call-graph proof across framework callbacks and generic scope runners.
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
        "callbacks.run(() -> tenantMapper.read());"
    })
    void dependencyContractRejectsDirectHelperEventAndCallbackTenantWork(String body) throws IOException {
        String source = "class UserController { Object userService; Object tenantMapper; Object helper; "
            + "Object events; Object callbacks; void getUserById() { userService.getUserById(); " + body + " } }";
        Map<String, Set<String>> actual = new HashMap<>();
        scan(List.of(new SourceText(source)), actual);

        assertThrows(AssertionError.class, () -> assertEquals(
            DEPENDENCIES.get("UserController#getUserById"), actual.get("UserController#getUserById")));
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
                        String key = type + "#" + tree.getName();
                        current = DEPENDENCIES.containsKey(key)
                            ? calls.computeIfAbsent(key, ignored -> new TreeSet<>()) : null;
                        try {
                            return super.visitMethod(tree, unused);
                        } finally {
                            current = previous;
                        }
                    }

                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
                        if (current != null) {
                            if (tree.getMethodSelect() instanceof IdentifierTree identifier) {
                                current.add("this." + identifier.getName());
                            } else if (tree.getMethodSelect() instanceof MemberSelectTree select) {
                                String receiver = select.getExpression().toString().replaceFirst("^this\\.", "");
                                if (fields.contains(receiver)) {
                                    current.add(receiver + "." + select.getIdentifier());
                                }
                            }
                        }
                        return super.visitMethodInvocation(tree, unused);
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
