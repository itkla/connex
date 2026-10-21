package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Prevents production code from bypassing attachment user-label hydration. */
class AttachmentReadBoundaryArchTest {
    private static ArchitectureSourceIndex sourceIndex;

    @BeforeAll
    static void readSourceSnapshot() throws IOException {
        sourceIndex = ArchitectureSourceIndex.read(Path.of("src/main/java/ooo/klae/connex/backend"));
    }

    private static final List<String> HYDRATED_CALLS = List.of(
        "attachmentMapper.getByEntity(",
        "attachmentMapper.getAll(",
        "attachmentMapper.getById(",
        "attachmentMapper.getByUrl(");

    @Test
    void onlyAttachmentReadServiceCallsHydrationSensitiveMapperReads() throws IOException {
        List<String> callers = sourceIndex.matching(source -> HYDRATED_CALLS.stream().anyMatch(source::contains)).stream()
            .map(path -> path.getFileName().toString())
            .sorted()
            .toList();

        assertEquals(List.of("AttachmentReadService.java"), callers);
    }

    @Test
    void onlyQueryReadServiceCallsPredicateSensitiveMapperReads() {
        List<String> calls = List.of("attachmentMapper.search(", "attachmentMapper.getPage(",
            "attachmentMapper.countPage(", "attachmentMapper.countOrphaned(", "attachmentMapper.getQueryUserIds(");
        List<String> callers = sourceIndex.matching(source -> calls.stream().anyMatch(source::contains)).stream()
            .map(path -> path.getFileName().toString()).sorted().toList();
        assertEquals(List.of("AttachmentQueryReadService.java"), callers);
    }

    @Test
    void onlyApprovedInternalWritersInsertAttachmentRows() throws IOException {
        List<String> callers = sourceIndex.matching(source -> source.contains("attachmentMapper.insert(")).stream()
            .map(path -> path.getFileName().toString())
            .sorted()
            .toList();

        assertEquals(List.of("AttachmentWriteOperations.java", "SeederBatchWriter.java"), callers);
    }

    @Test
    void onlyPermissionCheckedAttachmentServiceInvokesWriteOperations() throws IOException {
        List<String> callers = sourceIndex.matching(source -> source.contains("attachmentWriteOperations.")).stream()
            .map(path -> path.getFileName().toString())
            .sorted()
            .toList();

        assertEquals(
                List.of("AiChatAttachmentService.java", "AttachmentService.java"),
                callers);
    }
}
