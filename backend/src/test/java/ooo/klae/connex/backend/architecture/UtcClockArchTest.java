package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import org.junit.jupiter.api.Test;

/** Keeps backend clock reads independent of the host zone, including static imports. */
class UtcClockArchTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");
    private static final Set<String> FORBIDDEN_CALLS = Set.of(
        "LocalDateTime.now", "LocalDate.now", "LocalTime.now", "ZonedDateTime.now",
        "OffsetDateTime.now", "ZoneId.systemDefault", "TimeZone.getDefault", "Clock.systemDefaultZone");

    @Test
    void productionClockReadsUseExplicitZones() throws IOException {
        var compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler());
        try (var files = Files.walk(SOURCE_ROOT);
                var manager = compiler.getStandardFileManager(null, null, null)) {
            List<Path> sources = files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
            assertTrue(!sources.isEmpty(), "The production source scan must not be empty");
            assertEquals(List.of(), forbiddenCalls(manager.getJavaFileObjectsFromPaths(sources)));
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
                    ZoneId.systemDefault();
                    TimeZone.getDefault();
                    Clock.systemDefaultZone();
                    java.time.LocalDateTime . now ( );
                    now();
                    getDefault();
                }
            }
            """;
        assertEquals(11, forbiddenCalls(List.of(source("Sample.java", source))).size());
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
                    Instant.now();
                    String example = "LocalDateTime.now()";
                    /** ZoneId.systemDefault() */
                }
            }
            """;
        assertEquals(List.of(), forbiddenCalls(List.of(source("Sample.java", source))));
    }

    @Test
    void onlyTheExactApplicationPinningMethodIsExempt() throws IOException {
        String source = """
            package ooo.klae.connex.backend;
            class BackendApplication {
                static void pinDefaultTimeZone() { TimeZone.getDefault(); }
                static void pinDefaultTimeZoneLater() { TimeZone.getDefault(); }
            }
            """;
        assertEquals(1, forbiddenCalls(List.of(source("BackendApplication.java", source))).size());
        assertEquals(2, forbiddenCalls(List.of(source("Other.java", source))).size());
    }

    private static List<String> forbiddenCalls(Iterable<? extends JavaFileObject> sources) throws IOException {
        var compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler());
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        List<String> violations = new ArrayList<>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                List.of("-proc:none"), null, sources);
            var positions = Trees.instance(task).getSourcePositions();
            for (var unit : task.parse()) {
                List<String> staticImports = unit.getImports().stream().filter(tree -> tree.isStatic())
                    .map(tree -> tree.getQualifiedIdentifier().toString()).toList();
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitMethod(MethodTree method, Void unused) {
                        if (unit.getSourceFile().getName().endsWith("/BackendApplication.java")
                                && "ooo.klae.connex.backend".equals(String.valueOf(unit.getPackageName()))
                                && method.getName().contentEquals("pinDefaultTimeZone")
                                && method.getParameters().isEmpty()
                                && getCurrentPath().getParentPath().getLeaf() instanceof ClassTree owner
                                && owner.getSimpleName().contentEquals("BackendApplication")
                                && getCurrentPath().getParentPath().getParentPath().getLeaf() == unit) {
                            return null;
                        }
                        return super.visitMethod(method, unused);
                    }

                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                        if (call.getArguments().isEmpty() && isForbidden(call)) {
                            long line = unit.getLineMap().getLineNumber(positions.getStartPosition(unit, call));
                            violations.add(unit.getSourceFile().getName() + ":" + line + " " + call);
                        }
                        return super.visitMethodInvocation(call, unused);
                    }

                    private boolean isForbidden(MethodInvocationTree call) {
                        if (call.getMethodSelect() instanceof MemberSelectTree selection) {
                            String owner = selection.getExpression().toString();
                            return FORBIDDEN_CALLS.contains(simpleName(owner) + "." + selection.getIdentifier());
                        }
                        String method = call.getMethodSelect().toString();
                        return staticImports.stream().anyMatch(imported -> {
                            int separator = imported.lastIndexOf('.');
                            String owner = simpleName(imported.substring(0, separator));
                            String member = imported.substring(separator + 1);
                            return (member.equals(method) || member.equals("*"))
                                && FORBIDDEN_CALLS.contains(owner + "." + method);
                        });
                    }
                }.scan(unit, null);
            }
            assertTrue(diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR),
                () -> "Source parsing failed: " + diagnostics.getDiagnostics());
        }
        return violations;
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
}
