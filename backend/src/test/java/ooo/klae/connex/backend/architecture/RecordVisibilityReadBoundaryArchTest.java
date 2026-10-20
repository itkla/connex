package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;

/** Prevents read and mutation callbacks from rejoining through an adapter or a shared helper. */
class RecordVisibilityReadBoundaryArchTest {
    @Test
    void boundaryNeverConvertsBetweenReadAndMutationCallbacks() throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.exists(root.resolve("backend"))) {
            root = root.getParent();
        }
        String source = Files.readString(root.resolve(
            "backend/src/main/java/ooo/klae/connex/backend/services/RecordVisibilityScopeControlAccess.java"));
        assertEquals(List.of(), violations(source));
    }

    @Test
    void guardRejectsLambdaMethodReferenceAndReverseAdaptersThroughHelpers() throws IOException {
        for (String adapter : List.of(
                "Object read(RecordVisibilityRead read) { return write(() -> read.read()); }",
                "Object read(RecordVisibilityRead read) { return write(read::read); }",
                "Object write(Supplier work) { return read(() -> work.get()); }",
                "Object read(RecordVisibilityRead read) { return Scope.helper(read::read); }",
                "Object read(RecordVisibilityRead read) { java.util.function.Supplier work = read::read; return work.get(); }")) {
            String source = "class Scope { "
                + "Object read(RecordVisibilityRead read) { return read.read(); } "
                + "Object write(Supplier work) { return helper(work); } "
                + "Object helper(Supplier work) { return work.get(); } "
                + adapter + " }";
            assertFalse(violations(source).isEmpty(), adapter);
        }
    }

    private static List<String> violations(String source) throws IOException {
        var compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler());
        var file = new SimpleJavaFileObject(URI.create("string:///Scope.java"),
                javax.tools.JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        Map<String, Set<String>> types = new HashMap<>();
        Map<String, Set<String>> calls = new HashMap<>();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            var parser = (JavacTask) compiler.getTask(null, manager, null,
                List.of("-proc:none"), null, List.of(file));
            for (var unit : parser.parse()) {
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitMethod(MethodTree method, Void unused) {
                        String name = method.getName().toString();
                        Set<String> methodTypes = types.computeIfAbsent(name, key -> new HashSet<>());
                        Set<String> methodCalls = calls.computeIfAbsent(name, key -> new HashSet<>());
                        new TreeScanner<Void, Void>() {
                            @Override
                            public Void visitIdentifier(IdentifierTree identifier, Void ignored) {
                                String type = identifier.getName().toString();
                                if (Set.of("Supplier", "RecordVisibilityRead").contains(type)) {
                                    methodTypes.add(type);
                                }
                                return super.visitIdentifier(identifier, ignored);
                            }

                            @Override
                            public Void visitMemberSelect(MemberSelectTree member, Void ignored) {
                                String type = member.getIdentifier().toString();
                                if (Set.of("Supplier", "RecordVisibilityRead").contains(type)) {
                                    methodTypes.add(type);
                                }
                                return super.visitMemberSelect(member, ignored);
                            }

                            @Override
                            public Void visitMemberReference(MemberReferenceTree reference, Void ignored) {
                                methodCalls.add(reference.getName().toString());
                                return super.visitMemberReference(reference, ignored);
                            }

                            @Override
                            public Void visitMethodInvocation(MethodInvocationTree invocation, Void ignored) {
                                String select = invocation.getMethodSelect() instanceof MemberSelectTree member
                                    ? member.getIdentifier().toString() : invocation.getMethodSelect().toString();
                                methodCalls.add(select);
                                return super.visitMethodInvocation(invocation, ignored);
                            }
                        }.scan(method, null);
                        return null;
                    }
                }.scan(unit, null);
            }
        }
        List<String> violations = new ArrayList<>();
        for (var method : types.entrySet()) {
            for (String type : method.getValue()) {
                Set<String> pending = new HashSet<>(Set.of(method.getKey()));
                Set<String> visited = new HashSet<>();
                while (!pending.isEmpty()) {
                    String next = pending.iterator().next();
                    pending.remove(next);
                    if (!visited.add(next)) {
                        continue;
                    }
                    if (types.getOrDefault(next, Set.of()).stream().anyMatch(other -> !type.equals(other))) {
                        violations.add(method.getKey() + " reaches " + next + " through " + type);
                    }
                    pending.addAll(calls.getOrDefault(next, Set.of()));
                }
            }
        }
        return violations;
    }
}
