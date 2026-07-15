package ooo.klae.connex.backend.businesscard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

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

    private static OcrLine line(String text, double confidence, int y) {
        return new OcrLine(text, confidence, 20, y, 500, y + 40);
    }
}
