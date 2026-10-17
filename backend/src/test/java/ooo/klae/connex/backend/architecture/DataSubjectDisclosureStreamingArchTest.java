package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** Prevents reintroducing payload accumulation or an asynchronous snapshot escape. */
class DataSubjectDisclosureStreamingArchTest {
    private static final Path JAVA = Path.of("src/main/java/ooo/klae/connex/backend");

    @Test
    void disclosurePathKeepsPayloadsSynchronousAndNonAccumulating() throws Exception {
        Pattern accumulationOrAsync = Pattern.compile(
            "StringWriter|ByteArrayOutputStream|StringBuilder|StringBuffer|writeValueAs(?:String|Bytes)"
                + "|readAllBytes|StreamingResponseBody|CompletableFuture|Executor|@Async|supplyAsync|parallelStream");
        for (String file : List.of("controllers/DataSubjectRequestController.java",
                "services/DataSubjectRequestService.java", "services/DataSubjectDisclosureAccess.java",
                "services/DataSubjectDisclosureReadTransaction.java", "services/DataSubjectDisclosureJsonWriter.java",
                "dto/DataSubjectDisclosureDto.java", "dto/DataSubjectDisclosureNotes.java")) {
            String source = Files.readString(JAVA.resolve(file));
            assertFalse(accumulationOrAsync.matcher(source).find(), file);
            assertFalse(source.contains("JsonRawValue"), file);
        }
        String read = Files.readString(JAVA.resolve("services/DataSubjectDisclosureReadTransaction.java"));
        assertTrue(read.contains("isolation = Isolation.REPEATABLE_READ"));
        assertTrue(read.contains("try (DataSubjectDisclosureNotes notes"));
        String service = Files.readString(JAVA.resolve("services/DataSubjectRequestService.java"));
        assertTrue(service.contains("MAX_CONCURRENT_DISCLOSURES = 1"));
        assertTrue(service.contains("disclosureAdmission.tryAcquire()"));
        assertTrue(service.indexOf("disclosureAdmission.tryAcquire()") < service.indexOf("disclosureAccess.withSnapshot"));
        String writer = Files.readString(JAVA.resolve("services/DataSubjectDisclosureJsonWriter.java"));
        assertTrue(writer.contains("without(StreamWriteFeature.AUTO_CLOSE_CONTENT)"));
        assertTrue(writer.contains("without(StreamWriteFeature.AUTO_CLOSE_TARGET)"));
    }
}
