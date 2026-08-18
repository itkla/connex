package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.YieldTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import ooo.klae.connex.backend.observability.RequestPathRedactor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Ensures every backend-produced notification action URL targets a shipped frontend route and uses
 * only canonical deep-link query parameters. The gate resolves literal-prefixed concatenations,
 * variable assignments scoped to the enclosing method (with class-field initializers as the only
 * fallback), switch alternatives, and same-file helper returns resolved in the helper's own scope;
 * method parameters never resolve, so caller-supplied URLs fail closed, as does anything else that
 * cannot be reduced to those forms. DTO package calls are excluded because they copy an
 * already-produced notification into an outbound representation rather than construct a
 * notification destination.
 */
class NotificationActionUrlIntegrityTest {

    private static final String DYNAMIC = "<dynamic>";
    private static final String SET_ACTION_URL = "setActionUrl";
    private static final String DTO_PATH = "ooo/klae/connex/backend/dto/";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void everyNotificationActionUrlTargetsAShippedRouteWithCanonicalParameters() throws Exception {
        Path repositoryRoot = repositoryRoot();
        Path sourceRoot = repositoryRoot.resolve("backend/src/main/java");
        RouteManifest manifest = loadManifest(repositoryRoot);
        List<Path> javaFiles = javaSourceFiles(sourceRoot);
        assertTrue(javaFiles.size() >= 100,
            "Only scanned " + javaFiles.size() + " Java files; the source scan looks misconfigured.");

        List<String> violations = new ArrayList<>();
        int producerCalls = 0;
        for (Path file : javaFiles) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            if (!source.contains(SET_ACTION_URL)) {
                continue;
            }
            String sourcePath = displayPath(sourceRoot, file);
            ParsedSource parsed = parse(file);
            List<ActionUrlCall> calls = actionUrlCalls(parsed);
            if (sourcePath.startsWith(DTO_PATH)) {
                continue;
            }
            producerCalls += calls.size();
            UrlExpressionResolver resolver = new UrlExpressionResolver(parsed.unit());
            for (ActionUrlCall call : calls) {
                Set<String> shapes = resolver.resolve(call.argument(), call.scope());
                for (String shape : shapes) {
                    validateShape(
                        sourcePath + ":" + call.line(), call.argument().toString(), shape,
                        manifest, violations);
                }
            }
        }

        assertTrue(producerCalls > 0, "No notification action URL producers were found.");
        assertTrue(violations.isEmpty(),
            "Notification action URLs must target shipped routes and canonical parameters. "
                + "Make each URL statically resolvable or register its route shape explicitly: "
                + violations);
    }

    @Test
    void telemetryRouteVocabularyCoversEveryShippedAppRoute() throws Exception {
        RouteManifest manifest = loadManifest(repositoryRoot());
        List<String> unknown = new ArrayList<>();
        for (String route : manifest.routes()) {
            String concrete = route.replaceAll("\\[[^]/]+\\]", "123");
            if (RequestPathRedactor.UNKNOWN_ROUTE.equals(RequestPathRedactor.redact(concrete))) {
                unknown.add(route);
            }
        }
        assertTrue(unknown.isEmpty(),
            "RequestPathRedactor's route vocabulary is missing shipped app routes: " + unknown);
    }

    private static RouteManifest loadManifest(Path repositoryRoot) throws IOException {
        Path manifestPath = repositoryRoot.resolve(
            "backend/src/test/resources/frontend-route-manifest.json");
        JsonNode root = Objects.requireNonNull(
            OBJECT_MAPPER.readTree(manifestPath), "Route manifest must contain a JSON value");
        JsonNode paramsNode = root.path("params");
        JsonNode routesNode = root.path("routes");
        assertTrue(paramsNode.isObject(), "Route manifest params must be an object.");
        assertTrue(routesNode.isArray(), "Route manifest routes must be an array.");

        Set<String> parameters = new TreeSet<>();
        for (JsonNode value : paramsNode.values()) {
            assertTrue(value.isTextual(), "Route manifest parameter values must be strings.");
            parameters.add(value.asString());
        }
        List<String> routes = new ArrayList<>();
        for (JsonNode route : routesNode) {
            assertTrue(route.isTextual(), "Route manifest routes must be strings.");
            routes.add(route.asString());
        }
        routes.sort(String::compareTo);
        return new RouteManifest(Set.copyOf(parameters), List.copyOf(routes));
    }

    private static List<Path> javaSourceFiles(Path sourceRoot) throws IOException {
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            return files
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }
    }

    private static ParsedSource parse(Path file) throws IOException {
        JavaCompiler compiler = Objects.requireNonNull(
            ToolProvider.getSystemJavaCompiler(), "A JDK compiler is required for architecture tests");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> sources =
                fileManager.getJavaFileObjectsFromPaths(List.of(file));
            JavacTask task = (JavacTask) compiler.getTask(
                null, fileManager, diagnostics, List.of("-proc:none"), null, sources);
            List<CompilationUnitTree> units = new ArrayList<>();
            task.parse().forEach(units::add);
            List<String> errors = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(Diagnostic::toString)
                .toList();
            assertTrue(errors.isEmpty(), "Could not parse " + file + ": " + errors);
            assertTrue(units.size() == 1, "Expected one compilation unit for " + file);
            return new ParsedSource(units.getFirst(), Trees.instance(task).getSourcePositions());
        }
    }

    private static List<ActionUrlCall> actionUrlCalls(ParsedSource parsed) {
        List<ActionUrlCall> calls = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            private final Deque<MethodTree> enclosing = new ArrayDeque<>();

            @Override
            public Void visitMethod(MethodTree method, Void unused) {
                enclosing.push(method);
                try {
                    return super.visitMethod(method, unused);
                } finally {
                    enclosing.pop();
                }
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree invocation, Void unused) {
                if (isSetActionUrl(invocation)) {
                    assertTrue(invocation.getArguments().size() == 1,
                        "setActionUrl must have exactly one argument: " + invocation);
                    long position = parsed.positions().getStartPosition(parsed.unit(), invocation);
                    long line = position < 0 || parsed.unit().getLineMap() == null
                        ? -1 : parsed.unit().getLineMap().getLineNumber(position);
                    calls.add(new ActionUrlCall(
                        invocation.getArguments().getFirst(), line, enclosing.peek()));
                }
                return super.visitMethodInvocation(invocation, unused);
            }
        }.scan(parsed.unit(), null);
        return calls;
    }

    private static boolean isSetActionUrl(MethodInvocationTree invocation) {
        Tree method = invocation.getMethodSelect();
        return method instanceof MemberSelectTree selected
            && selected.getIdentifier().contentEquals(SET_ACTION_URL);
    }

    private static void validateShape(
        String location,
        String expression,
        String shape,
        RouteManifest manifest,
        List<String> violations
    ) {
        if (!shape.startsWith("/")) {
            violations.add(location + " cannot resolve `" + expression
                + "` to a literal-prefixed URL shape (resolved as `" + shape + "`)");
            return;
        }

        int fragmentIndex = shape.indexOf('#');
        String withoutFragment = fragmentIndex < 0 ? shape : shape.substring(0, fragmentIndex);
        int queryIndex = withoutFragment.indexOf('?');
        String path = queryIndex < 0 ? withoutFragment : withoutFragment.substring(0, queryIndex);
        String query = queryIndex < 0 ? null : withoutFragment.substring(queryIndex + 1);
        if (manifest.routes().stream().noneMatch(route -> routeMatches(path, route))) {
            violations.add(location + " resolves `" + expression + "` to unshipped path shape `"
                + displayShape(path) + "`");
        }
        if (query != null) {
            validateQuery(location, expression, query, manifest.parameters(), violations);
        }
    }

    private static boolean routeMatches(String shape, String route) {
        String[] shapeSegments = shape.split("/", -1);
        String[] routeSegments = route.split("/", -1);
        if (shapeSegments.length != routeSegments.length) {
            return false;
        }
        for (int index = 0; index < shapeSegments.length; index++) {
            String expected = routeSegments[index];
            String actual = shapeSegments[index];
            if (isDynamicRouteSegment(expected)) {
                if (!DYNAMIC.equals(actual) && !actual.matches("[0-9]+")) {
                    return false;
                }
            } else if (!expected.equals(actual)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDynamicRouteSegment(String segment) {
        return segment.length() > 2 && segment.startsWith("[") && segment.endsWith("]");
    }

    private static void validateQuery(
        String location,
        String expression,
        String query,
        Set<String> canonicalParameters,
        List<String> violations
    ) {
        if (query.isEmpty()) {
            violations.add(location + " resolves `" + expression + "` to an empty query string");
            return;
        }
        for (String parameter : query.split("&", -1)) {
            int equalsIndex = parameter.indexOf('=');
            String key = equalsIndex < 0 ? parameter : parameter.substring(0, equalsIndex);
            if (key.isEmpty() || key.contains(DYNAMIC) || !canonicalParameters.contains(key)) {
                violations.add(location + " resolves `" + expression
                    + "` to non-canonical query parameter `" + displayShape(key) + "`");
            }
        }
    }

    private static String displayShape(String shape) {
        return shape.replace(DYNAMIC, "[dynamic]");
    }

    private static String displayPath(Path root, Path file) {
        return root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
            .toString()
            .replace('\\', '/');
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("backend/settings.gradle"))) {
            current = current.getParent();
        }
        return Objects.requireNonNull(current, "Could not locate the repository root");
    }

    private static final class UrlExpressionResolver {

        private final CompilationUnitTree unit;

        private UrlExpressionResolver(CompilationUnitTree unit) {
            this.unit = unit;
        }

        private Set<String> resolve(ExpressionTree expression, MethodTree scope) {
            return resolve(expression, scope, new HashSet<>());
        }

        private Set<String> resolve(
            ExpressionTree expression,
            MethodTree scope,
            Set<String> visiting
        ) {
            if (expression instanceof ParenthesizedTree parenthesized) {
                return resolve(parenthesized.getExpression(), scope, visiting);
            }
            if (expression instanceof LiteralTree literal) {
                return resolveLiteral(literal);
            }
            if (expression instanceof BinaryTree binary && binary.getKind() == Tree.Kind.PLUS) {
                return concatenate(
                    resolve(binary.getLeftOperand(), scope, visiting),
                    resolve(binary.getRightOperand(), scope, visiting));
            }
            if (expression instanceof IdentifierTree identifier) {
                return resolveVariable(identifier.getName().toString(), scope, visiting);
            }
            if (expression instanceof ConditionalExpressionTree conditional) {
                Set<String> alternatives = new TreeSet<>(
                    resolve(conditional.getTrueExpression(), scope, visiting));
                alternatives.addAll(resolve(conditional.getFalseExpression(), scope, visiting));
                return alternatives;
            }
            if (expression instanceof SwitchExpressionTree switchExpression) {
                return resolveSwitch(switchExpression, scope, visiting);
            }
            if (expression instanceof MethodInvocationTree invocation) {
                return resolveHelper(invocation, visiting);
            }
            return Set.of(DYNAMIC);
        }

        private Set<String> resolveLiteral(LiteralTree literal) {
            Object value = literal.getValue();
            if (value instanceof String string) {
                return Set.of(string);
            }
            if (value instanceof Number number) {
                return Set.of(number.toString());
            }
            return Set.of(DYNAMIC);
        }

        private Set<String> resolveVariable(String name, MethodTree scope, Set<String> visiting) {
            if (scope != null && isParameter(scope, name)) {
                return Set.of(DYNAMIC);
            }
            String visitKey = "variable:" + name;
            if (!visiting.add(visitKey)) {
                return Set.of(DYNAMIC);
            }
            List<ExpressionTree> values = variableValues(name, scope);
            Set<String> resolved = new TreeSet<>();
            for (ExpressionTree value : values) {
                resolved.addAll(resolve(value, scope, visiting));
            }
            visiting.remove(visitKey);
            return resolved.isEmpty() ? Set.of(DYNAMIC) : resolved;
        }

        private static boolean isParameter(MethodTree scope, String name) {
            return scope.getParameters().stream()
                .anyMatch(parameter -> parameter.getName().contentEquals(name));
        }

        private List<ExpressionTree> variableValues(String name, MethodTree scope) {
            List<ExpressionTree> values = new ArrayList<>();
            Tree searchRoot = scope == null ? unit : scope.getBody();
            if (searchRoot != null) {
                collectAssignments(name, searchRoot, values);
            }
            if (values.isEmpty() && scope != null) {
                collectFieldInitializers(name, values);
            }
            return values;
        }

        private static void collectAssignments(String name, Tree root, List<ExpressionTree> values) {
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitVariable(VariableTree variable, Void unused) {
                    if (variable.getName().contentEquals(name) && variable.getInitializer() != null) {
                        values.add(variable.getInitializer());
                    }
                    return super.visitVariable(variable, unused);
                }

                @Override
                public Void visitAssignment(AssignmentTree assignment, Void unused) {
                    if (assignedName(assignment.getVariable()).equals(name)) {
                        values.add(assignment.getExpression());
                    }
                    return super.visitAssignment(assignment, unused);
                }
            }.scan(root, null);
        }

        private void collectFieldInitializers(String name, List<ExpressionTree> values) {
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree method, Void unused) {
                    return null;
                }

                @Override
                public Void visitVariable(VariableTree variable, Void unused) {
                    if (variable.getName().contentEquals(name) && variable.getInitializer() != null) {
                        values.add(variable.getInitializer());
                    }
                    return super.visitVariable(variable, unused);
                }
            }.scan(unit, null);
        }

        private static String assignedName(ExpressionTree variable) {
            if (variable instanceof IdentifierTree identifier) {
                return identifier.getName().toString();
            }
            if (variable instanceof MemberSelectTree selected) {
                return selected.getIdentifier().toString();
            }
            return "";
        }

        private Set<String> resolveSwitch(
            SwitchExpressionTree switchExpression,
            MethodTree scope,
            Set<String> visiting
        ) {
            Set<String> alternatives = new TreeSet<>();
            for (CaseTree caseTree : switchExpression.getCases()) {
                Tree body = caseTree.getBody();
                if (body instanceof ExpressionTree expression) {
                    alternatives.addAll(resolve(expression, scope, visiting));
                    continue;
                }
                List<ExpressionTree> yielded = new ArrayList<>();
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitYield(YieldTree yield, Void unused) {
                        yielded.add(yield.getValue());
                        return super.visitYield(yield, unused);
                    }
                }.scan(caseTree, null);
                for (ExpressionTree expression : yielded) {
                    alternatives.addAll(resolve(expression, scope, visiting));
                }
            }
            return alternatives.isEmpty() ? Set.of(DYNAMIC) : alternatives;
        }

        private Set<String> resolveHelper(MethodInvocationTree invocation, Set<String> visiting) {
            if (!(invocation.getMethodSelect() instanceof IdentifierTree identifier)) {
                return Set.of(DYNAMIC);
            }
            String methodName = identifier.getName().toString();
            String visitKey = "method:" + methodName;
            if (!visiting.add(visitKey)) {
                return Set.of(DYNAMIC);
            }
            Set<String> resolved = new TreeSet<>();
            for (MethodTree method : namedMethods(methodName)) {
                List<ExpressionTree> returns = new ArrayList<>();
                new ReturnExpressionScanner(returns).scan(method.getBody(), null);
                for (ExpressionTree expression : returns) {
                    resolved.addAll(resolve(expression, method, visiting));
                }
            }
            visiting.remove(visitKey);
            return resolved.isEmpty() ? Set.of(DYNAMIC) : resolved;
        }

        private List<MethodTree> namedMethods(String methodName) {
            List<MethodTree> methods = new ArrayList<>();
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree method, Void unused) {
                    if (method.getName().contentEquals(methodName) && method.getBody() != null) {
                        methods.add(method);
                    }
                    return super.visitMethod(method, unused);
                }
            }.scan(unit, null);
            return methods;
        }

        private Set<String> concatenate(Set<String> left, Set<String> right) {
            Set<String> combined = new TreeSet<>();
            for (String prefix : left) {
                for (String suffix : right) {
                    combined.add(prefix + suffix);
                }
            }
            return combined;
        }
    }

    private static final class ReturnExpressionScanner
            extends TreeScanner<Void, Void> {

        private final List<ExpressionTree> values;

        private ReturnExpressionScanner(List<ExpressionTree> values) {
            this.values = values;
        }

        @Override
        public Void visitReturn(ReturnTree returnTree, Void unused) {
            if (returnTree.getExpression() != null) {
                values.add(returnTree.getExpression());
            }
            return super.visitReturn(returnTree, unused);
        }

        @Override
        public Void visitClass(ClassTree classTree, Void unused) {
            return null;
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree lambda, Void unused) {
            return null;
        }
    }

    private record ActionUrlCall(ExpressionTree argument, long line, MethodTree scope) {}

    private record ParsedSource(CompilationUnitTree unit, SourcePositions positions) {}

    private record RouteManifest(Set<String> parameters, List<String> routes) {}
}
