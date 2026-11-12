package ooo.klae.connex.backend.businesscard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.dto.BusinessCardScanResponse;
import ooo.klae.connex.backend.dto.BusinessCardScanResponse.ExtractionOrigin;

class BusinessCardExtractorTest {
    private static final List<String> SCORED_FIELDS = List.of("name", "email", "phone", "title", "company");
    private static final Map<String, Double> RELEASE_GATES = Map.of(
            "email", 0.95,
            "phone", 0.95,
            "name", 0.85,
            "title", 0.8,
            "company", 0.8);
    private static final Pattern CJK_ADJACENT_SPACE = Pattern.compile(
            " (?=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}])|(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]) ");

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
        assertEquals(ExtractionOrigin.OCR, response.fields().name().origin());
        assertEquals(ExtractionOrigin.OCR, response.fields().email().origin());
        assertEquals(ExtractionOrigin.OCR, response.fields().phone().origin());
        assertEquals(ExtractionOrigin.OCR, response.fields().title().origin());
        assertEquals(ExtractionOrigin.OCR, response.company().origin());
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
        assertNull(response.fields().name().origin());
        assertNull(response.fields().email().origin());
        assertNull(response.fields().phone().origin());
        assertNull(response.fields().title().origin());
        assertNull(response.company().origin());
        assertEquals(List.of("no_recognizable_fields"), response.warnings());
    }

    @Test
    void perfectBenchmarkOcrMeetsTheDeterministicTitleGate() throws IOException {
        JsonNode cases = new ObjectMapper().readTree(benchmarkManifest().toFile()).path("cases");
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

    @Test
    void recordedBenchmarkOcrMeetsEveryReleaseAccuracyGate() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, JsonNode> expectedById = new HashMap<>();
        for (JsonNode benchmarkCase : mapper.readTree(benchmarkManifest().toFile()).path("cases")) {
            expectedById.put(benchmarkCase.path("id").asText(), benchmarkCase.path("fields"));
        }
        JsonNode recording;
        try (InputStream input = getClass().getResourceAsStream(
                "/fixtures/business-card-benchmark-ocr-lines.json")) {
            recording = mapper.readTree(input);
        }
        JsonNode recordedCases = recording.path("cases");
        assertEquals(expectedById.size(), recordedCases.size());

        Map<String, Integer> correct = new HashMap<>();
        Map<String, List<String>> misses = new HashMap<>();
        for (JsonNode recordedCase : recordedCases) {
            String id = recordedCase.path("id").asText();
            JsonNode expected = expectedById.get(id);
            assertTrue(expected != null, "Recorded OCR case is not in the canonical manifest: " + id);
            List<OcrLine> lines = new ArrayList<>();
            for (JsonNode recordedLine : recordedCase.path("lines")) {
                JsonNode box = recordedLine.path("box");
                lines.add(new OcrLine(
                        recordedLine.path("text").asText(),
                        recordedLine.path("confidence").asDouble(),
                        box.path(0).asInt(),
                        box.path(1).asInt(),
                        box.path(2).asInt(),
                        box.path(3).asInt()));
            }
            BusinessCardScanResponse response = extractor.extract(lines);
            Map<String, String> actual = new HashMap<>();
            actual.put("name", response.fields().name().value());
            actual.put("email", response.fields().email().value());
            actual.put("phone", response.fields().phone().value());
            actual.put("title", response.fields().title().value());
            actual.put("company", response.company().value());
            for (String field : SCORED_FIELDS) {
                if (benchmarkEqual(field, expected.path(field).asText(), actual.get(field))) {
                    correct.merge(field, 1, Integer::sum);
                } else {
                    misses.computeIfAbsent(field, ignored -> new ArrayList<>())
                            .add(id + "=" + actual.get(field));
                }
            }
        }

        for (String field : SCORED_FIELDS) {
            double accuracy = correct.getOrDefault(field, 0) / (double) recordedCases.size();
            assertTrue(accuracy >= RELEASE_GATES.get(field),
                    field + " accuracy " + accuracy + " is below the release gate; misses: "
                            + misses.getOrDefault(field, List.of()));
        }
    }

    @Test
    void separatesALabelFromTheEmailAddressItIntroduces() {
        assertEquals("ada.lovelace@example.test", emailFrom("EMAIL ada.lovelace@example.test"));
        assertEquals("ada.lovelace@example.test", emailFrom("E-mail: ada.lovelace@example.test"));
        assertEquals("ada.lovelace@example.test", emailFrom("メール：ada.lovelace@example.test"));
        assertEquals("ada.lovelace@example.test", emailFrom("EMAILada.lovelace@example.test"));
        assertEquals("ada.lovelace@example.test", emailFrom("ada. lovelace @ example.test"));
        assertEquals("mail.desk@example.test", emailFrom("mail.desk@example.test"));
        assertEquals("emailteam@example.test", emailFrom("emailteam@example.test"));
    }

    @Test
    void acceptsAJapaneseNameThatOcrReturnedWithoutTheSeparatingSpace() {
        BusinessCardScanResponse response = extractor.extract(List.of(
                line("株式会社みらい研究所", 0.99, 20),
                line("佐藤花子", 0.99, 80),
                line("代表取締役", 0.99, 140),
                line("EMAIL hanako.sato@example.test", 0.99, 200)));

        assertEquals("佐藤花子", response.fields().name().value());
        assertEquals("株式会社みらい研究所", response.company().value());
        assertEquals("代表取締役", response.fields().title().value());
    }

    @Test
    void prefersASpacedJapaneseNameOverAnUnspacedCandidate() {
        BusinessCardScanResponse response = extractor.extract(List.of(
                line("総務部", 0.99, 20),
                line("佐藤 花子", 0.99, 80)));

        assertEquals("佐藤 花子", response.fields().name().value());
    }

    private String emailFrom(String text) {
        return extractor.extract(List.of(line(text, 0.99, 20))).fields().email().value();
    }

    /**
     * Mirrors the release benchmark's field comparison in {@code ocr/benchmark/run_benchmark.py}.
     */
    private static boolean benchmarkEqual(String field, String expected, String actual) {
        if (actual == null) {
            return false;
        }
        if (field.equals("phone")) {
            return expected.replaceAll("\\D", "").equals(actual.replaceAll("\\D", ""));
        }
        return benchmarkNormalized(expected).equals(benchmarkNormalized(actual));
    }

    private static String benchmarkNormalized(String value) {
        String collapsed = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("\\s+", " ");
        return CJK_ADJACENT_SPACE.matcher(collapsed).replaceAll("");
    }

    private static Path benchmarkManifest() {
        return Path.of(System.getProperty("user.dir"))
                .resolve("../ocr/benchmark/manifest.json")
                .normalize();
    }

    private static OcrLine line(String text, double confidence, int y) {
        return new OcrLine(text, confidence, 20, y, 500, y + 40);
    }
}
