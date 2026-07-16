package ooo.klae.connex.backend.businesscard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.dto.BusinessCardScanResponse;

class BusinessCardExtractorTest {
    private final BusinessCardExtractor extractor = new BusinessCardExtractor();

    @Test
    void extractsEnglishCardFieldsDeterministically() {
        BusinessCardScanResponse response = extractor.extract(List.of(
                line("ANALYTICAL LABS", 0.97, 20),
                line("Ada Lovelace", 0.99, 80),
                line("Principal Engineer", 0.96, 140),
                line("ada.lovelace@example.test", 0.98, 200),
                line("Tel +1 (202) 555-0199", 0.95, 260)));

        assertEquals("Ada Lovelace", response.fields().name().value());
        assertEquals("ada.lovelace@example.test", response.fields().email().value());
        assertEquals("+12025550199", response.fields().phone().value());
        assertEquals("Principal Engineer", response.fields().title().value());
        assertEquals("ANALYTICAL LABS", response.company().value());
        assertTrue(response.warnings().isEmpty());
    }

    @Test
    void extractsJapaneseCardFieldsAndNormalizesFullWidthPunctuation() {
        BusinessCardScanResponse response = extractor.extract(List.of(
                line("株式会社みらい研究所", 0.96, 20),
                line("氏名：佐藤 花子", 0.95, 80),
                line("役職：代表取締役", 0.94, 140),
                line("hanako.sato@example.test", 0.98, 200),
                line("電話：03-5555-0117", 0.97, 260)));

        assertEquals("佐藤 花子", response.fields().name().value());
        assertEquals("hanako.sato@example.test", response.fields().email().value());
        assertEquals("0355550117", response.fields().phone().value());
        assertEquals("代表取締役", response.fields().title().value());
        assertEquals("株式会社みらい研究所", response.company().value());
    }

    @Test
    void ignoresFaxAndReportsPartialResult() {
        BusinessCardScanResponse response = extractor.extract(List.of(
                line("Name: Grace Hopper", 0.95, 20),
                line("Fax +1 202 555 0100", 0.99, 80)));

        assertEquals("Grace Hopper", response.fields().name().value());
        assertNull(response.fields().phone().value());
        assertTrue(response.warnings().contains("partial_result"));
    }

    @Test
    void treatsAnUnlabeledUppercasePersonAsANameWithoutInventingACompany() {
        BusinessCardScanResponse response = extractor.extract(List.of(
                line("ADA LOVELACE", 0.98, 20),
                line("Principal Engineer", 0.96, 80),
                line("ada.lovelace@example.test", 0.99, 140)));

        assertEquals("ADA LOVELACE", response.fields().name().value());
        assertNull(response.company().value());
    }

    @Test
    void reportsEmptyRecognitionWithoutInventingFields() {
        BusinessCardScanResponse response = extractor.extract(List.of(
                line("···", 0.43, 20)));

        assertNull(response.fields().name().value());
        assertNull(response.fields().email().value());
        assertNull(response.fields().phone().value());
        assertNull(response.fields().title().value());
        assertNull(response.company().value());
        assertEquals(List.of("no_recognizable_fields"), response.warnings());
    }

    @Test
    void perfectBenchmarkOcrMeetsTheDeterministicTitleGate() throws IOException {
        Path manifestPath = Path.of(System.getProperty("user.dir"))
                .resolve("../ocr/benchmark/manifest.json")
                .normalize();
        JsonNode cases = new ObjectMapper().readTree(manifestPath.toFile()).path("cases");
        int correctTitles = 0;
        List<String> missedCases = new ArrayList<>();
        for (JsonNode benchmarkCase : cases) {
            JsonNode fields = benchmarkCase.path("fields");
            BusinessCardScanResponse response = extractor.extract(List.of(
                    line(fields.path("company").asText(), 1, 20),
                    line(fields.path("name").asText(), 1, 80),
                    line(fields.path("title").asText(), 1, 140),
                    line("EMAIL " + fields.path("email").asText(), 1, 200),
                    line("TEL " + fields.path("phone").asText(), 1, 260)));
            if (fields.path("title").asText().equals(response.fields().title().value())) {
                correctTitles++;
            } else {
                missedCases.add(benchmarkCase.path("id").asText());
            }
        }

        assertTrue(cases.size() >= 40);
        assertTrue(correctTitles / (double) cases.size() >= 0.8,
                "Perfect OCR missed benchmark titles: " + missedCases);
    }

    private static OcrLine line(String text, double confidence, int y) {
        return new OcrLine(text, confidence, 20, y, 500, y + 40);
    }
}
