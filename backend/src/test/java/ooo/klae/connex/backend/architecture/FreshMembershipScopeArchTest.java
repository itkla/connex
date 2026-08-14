package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;

/** Keeps newly scoped cleanup mapper calls inside their routed production methods. */
class FreshMembershipScopeArchTest {
    private static final CallSite FRESH_MEMBERSHIP_CLEANUP = new CallSite(
        "ooo.klae.connex.backend.services.UserOffboardingService",
        "prepareFreshMembershipInWorkspace",
        List.of("int", "int"));
    private static final CallSite PROVIDER_REFERENCE_CLEANUP = new CallSite(
        "ooo.klae.connex.backend.connectedaccounts.capture.ProviderCapturePurgeService",
        "clearAccountReferences",
        List.of("int", "int"));
    private static final CallSite FRESH_MEMBERSHIP_SCOPE = new CallSite(
        "ooo.klae.connex.backend.services.UserOffboardingService",
        "prepareFreshMembership",
        List.of("int", "int"));
    private static final Map<String, Set<CallSite>> EXPECTED_CALLERS = Map.of(
        "clearWorkspacePolicyUpdater", Set.of(PROVIDER_REFERENCE_CLEANUP),
        "clearAccountReferences", Set.of(),
        "deletePinsForFreshMembership", Set.of(FRESH_MEMBERSHIP_CLEANUP),
        "deleteDefaultsForFreshMembership", Set.of(FRESH_MEMBERSHIP_CLEANUP),
        "deleteForFreshMembership", Set.of(FRESH_MEMBERSHIP_CLEANUP),
        "prepareFreshMembershipInWorkspace", Set.of(FRESH_MEMBERSHIP_SCOPE));
    private static final Map<EntryPoint, Set<CallSite>> EXPECTED_ENTRY_POINT_CALLERS = Map.of(
        new EntryPoint("InviteService", "createInvite", 4), Set.of(new CallSite(
            "ooo.klae.connex.backend.controllers.WorkspaceController",
            "invite",
            List.of("int", "CreateInviteRequest"))),
        new EntryPoint("InviteService", "acceptInvite", 2), Set.of(),
        new EntryPoint("InviteService", "acceptInviteByHash", 3), Set.of(new CallSite(
            "ooo.klae.connex.backend.controllers.InviteController",
            "accept",
            List.of("InviteFlowAcceptRequest", "String", "HttpServletRequest", "HttpServletResponse"))),
        new EntryPoint("InviteService", "addExistingMember", 4), Set.of(new CallSite(
            "ooo.klae.connex.backend.controllers.WorkspaceController",
            "addMember",
            List.of("int", "AddMemberRequest"))),
        new EntryPoint("InviteLinkService", "redeemLink", 2), Set.of(),
        new EntryPoint("InviteLinkService", "redeemLinkByHash", 3), Set.of(new CallSite(
            "ooo.klae.connex.backend.controllers.InviteLinkController",
            "accept",
            List.of("InviteFlowAcceptRequest", "String", "HttpServletRequest", "HttpServletResponse"))),
        new EntryPoint("SsoLoginService", "resolve", 7), Set.of(
            new CallSite(
                "ooo.klae.connex.backend.sso.SsoAuthenticationSuccessHandler",
                "handleOidc",
                List.of(
                    "OAuth2AuthenticationToken",
                    "OidcUser",
                    "HttpServletRequest",
                    "HttpServletResponse",
                    "String")),
            new CallSite(
                "ooo.klae.connex.backend.sso.SsoAuthenticationSuccessHandler",
                "handleSaml",
                List.of(
                    "Saml2AssertionAuthentication",
                    "HttpServletRequest",
                    "HttpServletResponse",
                    "String"))),
        new EntryPoint("FreshMembershipTransaction", "execute", 2), Set.of(
            new CallSite(
                "ooo.klae.connex.backend.services.InviteService",
                "createInvite",
                List.of("int", "User", "String", "String")),
            new CallSite(
                "ooo.klae.connex.backend.services.InviteService",
                "acceptInvite",
                List.of("String", "User")),
            new CallSite(
                "ooo.klae.connex.backend.services.InviteService",
                "acceptInviteByHash",
                List.of("String", "User", "Runnable")),
            new CallSite(
                "ooo.klae.connex.backend.services.InviteService",
                "addExistingMember",
                List.of("int", "int", "String", "String")),
            new CallSite(
                "ooo.klae.connex.backend.services.InviteLinkService",
                "redeemLink",
                List.of("String", "User")),
            new CallSite(
                "ooo.klae.connex.backend.services.InviteLinkService",
                "redeemLinkByHash",
                List.of("String", "User", "Runnable")),
            new CallSite(
                "ooo.klae.connex.backend.services.SsoLoginService",
                "resolve",
                List.of(
                    "String",
                    "String",
                    "String",
                    "String",
                    "boolean",
                    "int",
                    "String"))));

    @Test
    void newlyScopedCleanupCallsRemainInsideTheirRoutedBoundaries() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        List<Path> sourceFiles;
        try (var paths = Files.walk(sourceRoot)) {
            sourceFiles = paths
                .filter(path -> path.toString().endsWith(".java"))
                .sorted()
                .toList();
        }

        JavaCompiler compiler = Objects.requireNonNull(
            ToolProvider.getSystemJavaCompiler(), "system Java compiler");
        Map<String, Set<CallSite>> actualCallers = new LinkedHashMap<>();
        EXPECTED_CALLERS.keySet().forEach(
            method -> actualCallers.put(method, new LinkedHashSet<>()));
        Map<EntryPoint, Set<CallSite>> actualEntryPointCallers = new LinkedHashMap<>();
        EXPECTED_ENTRY_POINT_CALLERS.keySet().forEach(
            entryPoint -> actualEntryPointCallers.put(entryPoint, new LinkedHashSet<>()));
        Set<CallSite> transactionBoundEntryPointCallers = new LinkedHashSet<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                null, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(
                null,
                fileManager,
                null,
                List.of("-proc:none"),
                null,
                fileManager.getJavaFileObjectsFromPaths(sourceFiles));
            for (CompilationUnitTree unit : task.parse()) {
                new GuardedCallScanner(
                    unit,
                    actualCallers,
                    actualEntryPointCallers,
                    transactionBoundEntryPointCallers).scan(unit, null);
            }
        }

        assertEquals(
            EXPECTED_CALLERS,
            actualCallers,
            "Newly scoped mapper calls must stay inside the exact routed cleanup methods");
        assertEquals(
            EXPECTED_ENTRY_POINT_CALLERS,
            actualEntryPointCallers,
            "Fresh-membership entry points gained an unreviewed production caller");
        assertEquals(
            Set.of(),
            transactionBoundEntryPointCallers,
            "Fresh-membership entry points must be called before any outer transaction opens");
    }

    private record CallSite(String type, String method, List<String> parameterTypes) {}

    private record EntryPoint(String receiverType, String method, int argumentCount) {}

    private record MethodSignature(String name, List<String> parameterTypes) {}

    private record MethodContext(MethodSignature signature, boolean transactional) {}

    private static final class GuardedCallScanner extends TreeScanner<Void, Void> {
        private final String packageName;
        private final Map<String, Set<CallSite>> callers;
        private final Map<EntryPoint, Set<CallSite>> entryPointCallers;
        private final Set<CallSite> transactionBoundEntryPointCallers;
        private final Deque<String> types = new ArrayDeque<>();
        private final Deque<Map<String, String>> fields = new ArrayDeque<>();
        private final Deque<Map<String, String>> variables = new ArrayDeque<>();
        private final Deque<Boolean> transactionalTypes = new ArrayDeque<>();
        private final Deque<MethodContext> methods = new ArrayDeque<>();
        private int programmaticTransactionDepth;

        private GuardedCallScanner(
                CompilationUnitTree unit,
                Map<String, Set<CallSite>> callers,
                Map<EntryPoint, Set<CallSite>> entryPointCallers,
                Set<CallSite> transactionBoundEntryPointCallers) {
            packageName = unit.getPackageName() == null
                ? ""
                : unit.getPackageName().toString();
            this.callers = callers;
            this.entryPointCallers = entryPointCallers;
            this.transactionBoundEntryPointCallers = transactionBoundEntryPointCallers;
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            String simpleName = node.getSimpleName().toString();
            types.addLast(simpleName.isEmpty() ? "<anonymous>" : simpleName);
            Map<String, String> declaredFields = new HashMap<>();
            for (Tree member : node.getMembers()) {
                if (member instanceof VariableTree variable) {
                    String variableType = declaredType(variable);
                    if (!variableType.isEmpty()) {
                        declaredFields.put(variable.getName().toString(), variableType);
                    }
                }
            }
            fields.addLast(declaredFields);
            transactionalTypes.addLast(hasTransactionalAnnotation(node.getModifiers()));
            try {
                return super.visitClass(node, unused);
            } finally {
                transactionalTypes.removeLast();
                fields.removeLast();
                types.removeLast();
            }
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            MethodSignature signature = new MethodSignature(
                node.getName().toString(),
                node.getParameters().stream()
                    .map(parameter -> parameter.getType().toString())
                    .toList());
            boolean transactional = transactionalTypes.getLast()
                || hasTransactionalAnnotation(node.getModifiers());
            methods.addLast(new MethodContext(signature, transactional));
            Map<String, String> parameters = new HashMap<>();
            for (VariableTree parameter : node.getParameters()) {
                String parameterType = declaredType(parameter);
                if (!parameterType.isEmpty()) {
                    parameters.put(parameter.getName().toString(), parameterType);
                }
            }
            variables.addLast(parameters);
            try {
                return super.visitMethod(node, unused);
            } finally {
                variables.removeLast();
                methods.removeLast();
            }
        }

        @Override
        public Void visitBlock(BlockTree node, Void unused) {
            variables.addLast(new HashMap<>());
            try {
                return super.visitBlock(node, unused);
            } finally {
                variables.removeLast();
            }
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            if (!variables.isEmpty()) {
                String variableType = declaredType(node);
                if (!variableType.isEmpty()) {
                    variables.getLast().put(node.getName().toString(), variableType);
                }
            }
            return super.visitVariable(node, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            if (node.getMethodSelect() instanceof MemberSelectTree memberSelect) {
                record(memberSelect.getIdentifier().toString());
                recordEntryPoint(memberSelect, node.getArguments().size());
            } else if (node.getMethodSelect() instanceof IdentifierTree identifier) {
                record(identifier.getName().toString());
            }
            boolean opensTransaction = opensProgrammaticTransaction(node);
            if (opensTransaction) {
                programmaticTransactionDepth++;
            }
            try {
                return super.visitMethodInvocation(node, unused);
            } finally {
                if (opensTransaction) {
                    programmaticTransactionDepth--;
                }
            }
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree node, Void unused) {
            record(node.getName().toString());
            return super.visitMemberReference(node, unused);
        }

        private void record(String methodName) {
            Set<CallSite> methodCallers = callers.get(methodName);
            if (methodCallers == null) {
                return;
            }
            methodCallers.add(currentCallSite());
        }

        private void recordEntryPoint(MemberSelectTree select, int argumentCount) {
            String receiverName = receiverName(select.getExpression());
            String receiverType = variableType(receiverName);
            if (receiverType == null) {
                return;
            }
            EntryPoint entryPoint = new EntryPoint(
                receiverType, select.getIdentifier().toString(), argumentCount);
            Set<CallSite> methodCallers = entryPointCallers.get(entryPoint);
            if (methodCallers == null) {
                return;
            }
            CallSite callSite = currentCallSite();
            methodCallers.add(callSite);
            if (programmaticTransactionDepth > 0
                    || (!methods.isEmpty() && methods.getLast().transactional())) {
                transactionBoundEntryPointCallers.add(callSite);
            }
        }

        private boolean opensProgrammaticTransaction(MethodInvocationTree node) {
            if (!(node.getMethodSelect() instanceof MemberSelectTree select)) {
                return false;
            }
            String methodName = select.getIdentifier().toString();
            if (!methodName.equals("execute") && !methodName.equals("executeWithoutResult")) {
                return false;
            }
            if (select.getExpression() instanceof NewClassTree constructor) {
                return simpleType(constructor.getIdentifier().toString())
                    .equals("TransactionTemplate");
            }
            String receiverType = variableType(receiverName(select.getExpression()));
            return "TransactionTemplate".equals(receiverType)
                || "TransactionOperations".equals(receiverType);
        }

        private CallSite currentCallSite() {
            List<String> nestedTypes = new ArrayList<>(types);
            String qualifiedType = packageName.isEmpty()
                ? String.join("$", nestedTypes)
                : packageName + "." + String.join("$", nestedTypes);
            MethodSignature signature = methods.isEmpty()
                ? new MethodSignature("<initializer>", List.of())
                : methods.getLast().signature();
            return new CallSite(
                qualifiedType,
                signature.name(),
                signature.parameterTypes());
        }

        private String variableType(String variableName) {
            if (variableName == null) {
                return null;
            }
            Iterator<Map<String, String>> localScopes = variables.descendingIterator();
            while (localScopes.hasNext()) {
                String type = localScopes.next().get(variableName);
                if (type != null) {
                    return type;
                }
            }
            Iterator<Map<String, String>> scopes = fields.descendingIterator();
            while (scopes.hasNext()) {
                String type = scopes.next().get(variableName);
                if (type != null) {
                    return type;
                }
            }
            return null;
        }

        private static String receiverName(Tree receiver) {
            if (receiver instanceof IdentifierTree identifier) {
                return identifier.getName().toString();
            }
            if (receiver instanceof MemberSelectTree memberSelect
                    && memberSelect.getExpression() instanceof IdentifierTree identifier
                    && (identifier.getName().contentEquals("this")
                        || identifier.getName().contentEquals("super"))) {
                return memberSelect.getIdentifier().toString();
            }
            return null;
        }

        private static boolean hasTransactionalAnnotation(ModifiersTree modifiers) {
            return modifiers.getAnnotations().stream()
                .map(annotation -> annotation.getAnnotationType().toString())
                .anyMatch(name -> name.equals("Transactional") || name.endsWith(".Transactional"));
        }

        private static String declaredType(VariableTree variable) {
            Tree type = variable.getType();
            if (type == null) {
                return "";
            }
            String resolvedType = simpleType(type.toString());
            if (resolvedType.equals("var")
                    && variable.getInitializer() instanceof NewClassTree constructor) {
                return simpleType(constructor.getIdentifier().toString());
            }
            return resolvedType;
        }

        private static String simpleType(String typeName) {
            int generic = typeName.indexOf('<');
            String rawType = generic < 0 ? typeName : typeName.substring(0, generic);
            int packageSeparator = rawType.lastIndexOf('.');
            return packageSeparator < 0 ? rawType : rawType.substring(packageSeparator + 1);
        }
    }
}
