package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Prevents production code from bypassing attachment user-label hydration. */
class AttachmentReadBoundaryArchTest {
    private static final List<String> HYDRATED_CALLS = List.of(
        "attachmentMapper.getByEntity(",
        "attachmentMapper.getAll(",
        "attachmentMapper.getById(",
        "attachmentMapper.getByUrl(");

    @Test
    void onlyAttachmentReadServiceCallsHydrationSensitiveMapperReads() throws IOException {
        List<String> callers = new ArrayList<>();
        Path sourceRoot = Path.of("src/main/java/ooo/klae/connex/backend");
        try (var paths = Files.walk(sourceRoot)) {
            for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                if (HYDRATED_CALLS.stream().anyMatch(source::contains)) {
                    callers.add(path.getFileName().toString());
                }
            }
        }
        callers.sort(String::compareTo);

        assertEquals(List.of("AttachmentReadService.java"), callers);
    }

    @Test
    void onlyApprovedInternalWritersInsertAttachmentRows() throws IOException {
        List<String> callers = new ArrayList<>();
        Path sourceRoot = Path.of("src/main/java/ooo/klae/connex/backend");
        try (var paths = Files.walk(sourceRoot)) {
            for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                if (source.contains("attachmentMapper.insert(")) {
                    callers.add(path.getFileName().toString());
                }
            }
        }
        callers.sort(String::compareTo);

        assertEquals(List.of("AttachmentWriteOperations.java", "SeederBatchWriter.java"), callers);
    }

    @Test
    void onlyPermissionCheckedAttachmentServiceInvokesWriteOperations() throws IOException {
        List<String> callers = new ArrayList<>();
        Path sourceRoot = Path.of("src/main/java/ooo/klae/connex/backend");
        try (var paths = Files.walk(sourceRoot)) {
            for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                if (source.contains("attachmentWriteOperations.")) {
                    callers.add(path.getFileName().toString());
                }
            }
        }
        callers.sort(String::compareTo);

        assertEquals(
                List.of("AiChatAttachmentService.java", "AttachmentService.java"),
                callers);
    }
}
