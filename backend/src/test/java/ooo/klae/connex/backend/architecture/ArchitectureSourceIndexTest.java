package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchitectureSourceIndexTest {
    @Test
    void snapshotKeepsRawScopedSourcesAndReturnsIndependentSortedResults(@TempDir Path root) throws Exception {
        Path scoped = Files.createDirectories(root.resolve("scoped"));
        Path first = scoped.resolve("A.java");
        Path second = scoped.resolve("Z.java");
        Files.writeString(first, "/** raw needle 日本語 */ class A {}");
        Files.writeString(second, "class Z { String value = \"needle\"; }");
        Files.writeString(scoped.resolve("ignored.txt"), "needle");
        Files.writeString(root.resolve("Outside.java"), "needle");
        ArchitectureSourceIndex index = ArchitectureSourceIndex.read(scoped);
        List<Path> matches = index.containing("needle");

        assertEquals(List.of(first, second), matches);
        matches.clear();
        Files.writeString(first, "changed after snapshot");
        assertEquals(List.of(first, second), index.containing("needle"));
        assertEquals("/** raw needle 日本語 */ class A {}", index.source(first));
    }
}
