package ooo.klae.connex.backend.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Immutable raw Java source snapshot owned by one architecture test class. */
final class ArchitectureSourceIndex {
    private final Map<Path, String> sources;

    private ArchitectureSourceIndex(Map<Path, String> sources) {
        this.sources = Map.copyOf(sources);
    }

    static ArchitectureSourceIndex read(Path root) throws IOException {
        Map<Path, String> sources = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".java"))
                    .sorted().toList()) {
                sources.put(path, Files.readString(path, StandardCharsets.UTF_8));
            }
        }
        return new ArchitectureSourceIndex(sources);
    }

    String source(Path path) {
        String source = sources.get(path);
        if (source == null) {
            throw new IllegalArgumentException("Source absent from snapshot: " + path);
        }
        return source;
    }

    List<Path> containing(String token) {
        return matching(source -> source.contains(token));
    }

    List<Path> matching(Predicate<String> predicate) {
        return new ArrayList<>(sources.entrySet().stream()
            .filter(entry -> predicate.test(entry.getValue()))
            .map(Map.Entry::getKey)
            .sorted()
            .toList());
    }
}
