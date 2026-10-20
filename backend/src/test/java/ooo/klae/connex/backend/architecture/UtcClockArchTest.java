package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import org.junit.jupiter.api.Test;

/** Keeps clock reads, formatter choices and every application entry point independent of the host zone. */
class UtcClockArchTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final String APPLICATION = "ooo.klae.connex.backend.BackendApplication";
    private static final String PIN_METHOD = "pinDefaultTimeZone";
    private static final String SERVLET_INITIALIZER =
        "org.springframework.boot.web.servlet.support.SpringBootServletInitializer";
    private static final String APPLICATION_BUILDER = "org.springframework.boot.builder.SpringApplicationBuilder";
    private static final Set<String> FORBIDDEN_CALLS = Set.of(
        "LocalDateTime.now", "LocalDate.now", "LocalTime.now", "ZonedDateTime.now", "OffsetDateTime.now",
        "YearMonth.now", "Year.now", "MonthDay.now",
        "ZoneId.systemDefault", "TimeZone.getDefault", "Clock.systemDefaultZone");
    private static final Set<String> LEGACY_FORMATTERS = Set.of("SimpleDateFormat", "DateFormat");

    @Test
    void productionClocksFormattersAndEntryPointsUseExplicitUtcPolicy() throws IOException {
        var compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler());
        try (var files = Files.walk(SOURCE_ROOT);
                var manager = compiler.getStandardFileManager(null, null, null)) {
            List<Path> sources = files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
            assertFalse(sources.isEmpty(), "The production source scan must not be empty");
            assertEquals(List.of(), violations(manager.getJavaFileObjectsFromPaths(sources)));
        }
    }

    @Test
    void detectorFindsEveryForbiddenCallIncludingQualifiedAndStaticImports() throws IOException {
        String source = """
            import static java.time.LocalDateTime.now;
            import static java.util.TimeZone.*;
            class Sample {
                void sample() {
                    LocalDateTime.now();
                    LocalDate.now();
                    LocalTime.now();
                    ZonedDateTime.now();
                    OffsetDateTime.now();
                    YearMonth.now();
                    Year.now();
                    MonthDay.now();
                    ZoneId.systemDefault();
                    TimeZone.getDefault();
                    Clock.systemDefaultZone();
                    java.time.LocalDateTime . now ( );
                    now();
                    getDefault();
                }
            }
            """;
        assertEquals(14, violations(List.of(source("Sample.java", source))).size());
    }

    @Test
    void detectorRejectsTemporalMethodReferences() throws IOException {
        for (String type : List.of("LocalDateTime", "LocalDate", "LocalTime", "ZonedDateTime", "OffsetDateTime",
                "YearMonth", "Year", "MonthDay")) {
            String source = "class Sample { Object clock = java.time." + type + "::now; }";
            assertEquals(1, violations(List.of(source("Sample.java", source))).size(), type);
        }
    }

    @Test
    void detectorRejectsLegacyFormatterImportsTypesConstructionAndStaticUses() throws IOException {
        for (String type : LEGACY_FORMATTERS) {
            for (String source : List.of(
                    "import java.text." + type + "; class Sample {}",
                    "import java.text.*; class Sample { " + type + " formatter; }",
                    "class Sample { Object formatter = new java.text." + type + "(); }",
                    "class Sample { Object formatter = java.text." + type + ".getInstance(); }")) {
                assertFalse(violations(List.of(source("Sample.java", source))).isEmpty(), source);
            }
        }
    }

    @Test
    void explicitReadsInstantsCommentsAndStringsAreAllowed() throws IOException {
        String source = """
            class Sample {
                void sample() {
                    LocalDateTime.now(ZoneOffset.UTC);
                    LocalDate.now(clock);
                    LocalTime.now(ZoneOffset.UTC);
                    ZonedDateTime.now(ZoneOffset.UTC);
                    OffsetDateTime.now(ZoneOffset.UTC);
                    YearMonth.now(clock);
                    Year.now(clock);
                    MonthDay.now(clock);
                    Instant.now();
                    Object instant = Instant::now;
                    String example = "LocalDateTime.now() java.text.DateFormat";
                    /** ZoneId.systemDefault() */
                }
            }
            """;
        assertEquals(List.of(), violations(List.of(source("Sample.java", source))));
    }

    @Test
    void onlyDefaultZoneInspectionInTheExactPinningMethodIsExempt() throws IOException {
        String source = """
            package ooo.klae.connex.backend;
            class BackendApplication {
                static void pinDefaultTimeZone() {
                    TimeZone.getDefault();
                    LocalDate.now();
                    Object clock = LocalDateTime::now;
                    Object formatter = new java.text.SimpleDateFormat();
                }
                static void pinDefaultTimeZoneLater() { TimeZone.getDefault(); }
            }
            """;
        assertEquals(4, violations(List.of(source("BackendApplication.java", source))).size());
        assertEquals(5, violations(List.of(source("Other.java", source))).size());
    }

    @Test
    void everyMainAndDirectOrIndirectServletInitializerPinsBeforeOtherWork() throws IOException {
        String source = """
            package example;
            import ooo.klae.connex.backend.BackendApplication;
            import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
            import org.springframework.boot.builder.SpringApplicationBuilder;
            class Launcher {
                public static void main(String[] args) {
                    BackendApplication.pinDefaultTimeZone();
                    launch();
                }
            }
            class War extends SpringBootServletInitializer {
                protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
                    BackendApplication.pinDefaultTimeZone();
                    return builder;
                }
            }
            class ChildWar extends War {
                protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
                    BackendApplication.pinDefaultTimeZone();
                    return builder;
                }
            }
            """;
        assertEquals(List.of(), violations(List.of(source("Launchers.java", source))));
        assertEquals(3, violations(List.of(source("Launchers.java",
            source.replace("BackendApplication.pinDefaultTimeZone();", "")))).size());
        assertEquals(3, violations(List.of(source("Launchers.java", source
            .replace("extends SpringBootServletInitializer", "extends @Marker SpringBootServletInitializer")
            .replace("BackendApplication.pinDefaultTimeZone();", "")))).size());
        assertEquals(1, violations(List.of(source("Launchers.java", source.replace(
            "BackendApplication.pinDefaultTimeZone();\n        launch();",
            "launch();\n        BackendApplication.pinDefaultTimeZone();")))).size());
        assertEquals(1, violations(List.of(source("Launchers.java", source.replace(
            "public static void main(String[] args)", "static void main(java.lang.String... args)")
            .replace("BackendApplication.pinDefaultTimeZone();\n        launch();", "launch();")))).size());
        assertEquals(2, violations(List.of(source("Launchers.java", source.replace(
            "configure(SpringApplicationBuilder builder)", "configure(String builder)")))).size());
        String decoyOverload = source.replace(
            "BackendApplication.pinDefaultTimeZone();\n        return builder;", "return builder;")
            .replace("class War extends SpringBootServletInitializer {", """
                class War extends SpringBootServletInitializer {
                    protected SpringApplicationBuilder configure(String ignored) {
                        BackendApplication.pinDefaultTimeZone();
                        return null;
                    }
                """);
        assertEquals(2, violations(List.of(source("Launchers.java", decoyOverload))).size());
        assertEquals(2, violations(List.of(source("Launchers.java", source.replace(
            "BackendApplication.pinDefaultTimeZone();\n        return builder;",
            "return builder;")))).size());
        assertEquals(1, violations(List.of(source("Launchers.java", source.replace(
            "public static void main(String[] args)", "public static void main(String @Deprecated [] args)")
            .replace("BackendApplication.pinDefaultTimeZone();\n        launch();", "launch();")))).size());
    }

    private static List<String> violations(Iterable<? extends JavaFileObject> sources) throws IOException {
        var compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler());
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        List<String> violations = new ArrayList<>();
        List<SourceType> types = new ArrayList<>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                List.of("-proc:none"), null, sources);
            var positions = Trees.instance(task).getSourcePositions();
            for (var unit : task.parse()) {
                List<String> staticImports = unit.getImports().stream().filter(tree -> tree.isStatic())
                    .map(tree -> tree.getQualifiedIdentifier().toString()).toList();
                new TreePathScanner<Void, Void>() {
                    private boolean inPinningMethod;

                    @Override
                    public Void visitClass(ClassTree type, Void unused) {
                        if (!type.getSimpleName().isEmpty()) {
                            types.add(new SourceType(typeName(getCurrentPath(), unit), type, unit));
                        }
                        return super.visitClass(type, unused);
                    }

                    @Override
                    public Void visitMethod(MethodTree method, Void unused) {
                        boolean previous = inPinningMethod;
                        inPinningMethod = unit.getSourceFile().getName().endsWith("/BackendApplication.java")
                                && "ooo.klae.connex.backend".equals(String.valueOf(unit.getPackageName()))
                                && method.getName().contentEquals(PIN_METHOD)
                                && method.getParameters().isEmpty()
                                && method.getModifiers().getFlags().contains(Modifier.STATIC)
                                && method.getReturnType() != null && method.getReturnType().toString().equals("void")
                                && getCurrentPath().getParentPath().getLeaf() instanceof ClassTree owner
                                && owner.getSimpleName().contentEquals("BackendApplication")
                                && getCurrentPath().getParentPath().getParentPath().getLeaf() == unit;
                        try {
                            return super.visitMethod(method, unused);
                        } finally {
                            inPinningMethod = previous;
                        }
                    }

                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                        if (call.getArguments().isEmpty() && isForbidden(call)) {
                            report(call, call.toString());
                        }
                        return super.visitMethodInvocation(call, unused);
                    }

                    @Override
                    public Void visitMemberReference(MemberReferenceTree reference, Void unused) {
                        if (FORBIDDEN_CALLS.contains(simpleName(reference.getQualifierExpression().toString())
                                + "." + reference.getName())) {
                            report(reference, reference.toString());
                        }
                        return super.visitMemberReference(reference, unused);
                    }

                    @Override
                    public Void visitIdentifier(IdentifierTree identifier, Void unused) {
                        if (LEGACY_FORMATTERS.contains(identifier.getName().toString())) {
                            report(identifier, "legacy formatter " + identifier);
                        }
                        return super.visitIdentifier(identifier, unused);
                    }

                    @Override
                    public Void visitMemberSelect(MemberSelectTree selection, Void unused) {
                        if (LEGACY_FORMATTERS.stream().anyMatch(type -> selection.toString().equals("java.text." + type))) {
                            report(selection, "legacy formatter " + selection);
                        }
                        return super.visitMemberSelect(selection, unused);
                    }

                    private void report(com.sun.source.tree.Tree tree, String reason) {
                        long line = unit.getLineMap().getLineNumber(positions.getStartPosition(unit, tree));
                        violations.add(unit.getSourceFile().getName() + ":" + line + " " + reason);
                    }

                    private boolean isDisallowed(String call) {
                        return FORBIDDEN_CALLS.contains(call)
                            && !(inPinningMethod && call.equals("TimeZone.getDefault"));
                    }

                    private boolean isForbidden(MethodInvocationTree call) {
                        if (call.getMethodSelect() instanceof MemberSelectTree selection) {
                            return isDisallowed(simpleName(selection.getExpression().toString())
                                + "." + selection.getIdentifier());
                        }
                        String method = call.getMethodSelect().toString();
                        return staticImports.stream().anyMatch(imported -> {
                            int separator = imported.lastIndexOf('.');
                            String owner = simpleName(imported.substring(0, separator));
                            String member = imported.substring(separator + 1);
                            return (member.equals(method) || member.equals("*"))
                                && isDisallowed(owner + "." + method);
                        });
                    }
                }.scan(unit, null);
            }
            assertTrue(diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR),
                () -> "Source parsing failed: " + diagnostics.getDiagnostics());
        }
        Map<String, SourceType> knownTypes = new HashMap<>();
        types.forEach(type -> knownTypes.put(type.name(), type));
        for (SourceType type : types) {
            List<MethodTree> methods = type.tree().getMembers().stream()
                .filter(MethodTree.class::isInstance).map(MethodTree.class::cast).toList();
            for (MethodTree method : methods) {
                if (isMain(method) && !pinsFirst(method, type, knownTypes)) {
                    violations.add(type.name() + "#main must pin UTC before other work");
                }
            }
            if (isServletInitializer(type, knownTypes, new HashSet<>())
                    && methods.stream().noneMatch(method -> isConfigure(method, type, knownTypes)
                        && pinsFirst(method, type, knownTypes))) {
                violations.add(type.name() + "#configure must pin UTC before other work");
            }
        }
        return violations;
    }

    private static boolean isMain(MethodTree method) {
        return method.getName().contentEquals("main") && method.getModifiers().getFlags().contains(Modifier.STATIC)
            && method.getReturnType() != null && method.getReturnType().toString().equals("void")
            && method.getParameters().size() == 1
            && unannotated(method.getParameters().getFirst().getType()) instanceof ArrayTypeTree array
            && simpleName(unannotated(array.getType()).toString()).equals("String");
    }

    private static boolean isConfigure(MethodTree method, SourceType type, Map<String, SourceType> knownTypes) {
        return method.getName().contentEquals("configure")
            && !method.getModifiers().getFlags().contains(Modifier.STATIC)
            && method.getParameters().size() == 1 && method.getReturnType() != null
            && resolveType(unannotated(method.getReturnType()).toString(), type.unit(), knownTypes)
                .equals(APPLICATION_BUILDER)
            && resolveType(unannotated(method.getParameters().getFirst().getType()).toString(), type.unit(), knownTypes)
                .equals(APPLICATION_BUILDER);
    }

    private static Tree unannotated(Tree type) {
        return type instanceof AnnotatedTypeTree annotated ? unannotated(annotated.getUnderlyingType()) : type;
    }

    private static boolean pinsFirst(MethodTree method, SourceType type, Map<String, SourceType> knownTypes) {
        if (method.getBody() == null || method.getBody().getStatements().isEmpty()
                || !(method.getBody().getStatements().getFirst() instanceof ExpressionStatementTree statement)
                || !(statement.getExpression() instanceof MethodInvocationTree call) || !call.getArguments().isEmpty()) {
            return false;
        }
        if (call.getMethodSelect() instanceof MemberSelectTree selection) {
            return selection.getIdentifier().contentEquals(PIN_METHOD)
                && resolveType(selection.getExpression().toString(), type.unit(), knownTypes).equals(APPLICATION);
        }
        return call.getMethodSelect().toString().equals(PIN_METHOD) && type.name().equals(APPLICATION);
    }

    private static boolean isServletInitializer(SourceType type, Map<String, SourceType> knownTypes, Set<String> seen) {
        if (!seen.add(type.name()) || type.tree().getExtendsClause() == null) {
            return false;
        }
        String parent = resolveType(unannotated(type.tree().getExtendsClause()).toString(), type.unit(), knownTypes);
        if (parent.equals(SERVLET_INITIALIZER)) {
            return true;
        }
        SourceType declaration = knownTypes.get(parent);
        return declaration != null && isServletInitializer(declaration, knownTypes, seen);
    }

    private static String resolveType(String type, CompilationUnitTree unit, Map<String, SourceType> knownTypes) {
        String raw = type.replaceAll("<.*>", "");
        String first = raw.contains(".") ? raw.substring(0, raw.indexOf('.')) : raw;
        for (var imported : unit.getImports()) {
            if (imported.isStatic()) {
                continue;
            }
            String name = imported.getQualifiedIdentifier().toString();
            if (simpleName(name).equals(first)) {
                return name + raw.substring(first.length());
            }
            if (name.endsWith(".*")) {
                String candidate = name.substring(0, name.length() - 1) + raw;
                if (candidate.equals(SERVLET_INITIALIZER) || candidate.equals(APPLICATION_BUILDER)
                        || knownTypes.containsKey(candidate)) {
                    return candidate;
                }
            }
        }
        if (raw.contains(".") && Character.isLowerCase(raw.charAt(0))) {
            return raw;
        }
        return unit.getPackageName() == null ? raw : unit.getPackageName() + "." + raw;
    }

    private static String typeName(TreePath path, CompilationUnitTree unit) {
        List<String> names = new ArrayList<>();
        for (TreePath current = path; current != null; current = current.getParentPath()) {
            if (current.getLeaf() instanceof ClassTree type && !type.getSimpleName().isEmpty()) {
                names.addFirst(type.getSimpleName().toString());
            }
        }
        return (unit.getPackageName() == null ? "" : unit.getPackageName() + ".") + String.join(".", names);
    }

    private static String simpleName(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    private static JavaFileObject source(String name, String content) {
        return new SimpleJavaFileObject(URI.create("string:///" + name), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return content;
            }
        };
    }

    private record SourceType(String name, ClassTree tree, CompilationUnitTree unit) {}
}
