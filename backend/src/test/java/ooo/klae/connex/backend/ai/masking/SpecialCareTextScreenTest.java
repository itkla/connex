package ooo.klae.connex.backend.ai.masking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class SpecialCareTextScreenTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void englishFixturesReturnCategoryOnlyVerdicts() throws Exception {
        List<Fixture> fixtures = List.of(
                new Fixture(SpecialCareCategory.MEDICAL, "The note includes a diagnosis from last week.", "diagnosis"),
                new Fixture(SpecialCareCategory.CRIMINAL_RECORD, "The candidate disclosed a criminal record.",
                        "criminal record"),
                new Fixture(SpecialCareCategory.SOCIAL_STATUS, "The profile says public assistance is active.",
                        "public assistance"),
                new Fixture(SpecialCareCategory.DISABILITY, "The contact requested wheelchair access.", "wheelchair"),
                new Fixture(SpecialCareCategory.UNION, "The employee is a trade union representative.",
                        "trade union"),
                new Fixture(SpecialCareCategory.RELIGION, "The lead mentioned a religious belief.", "religious belief"),
                new Fixture(SpecialCareCategory.RACE_ETHNICITY, "The form recorded ethnic origin.", "ethnic origin"));

        assertFixtures(fixtures);
    }

    @Test
    void japaneseFixturesReturnCategoryOnlyVerdicts() throws Exception {
        List<Fixture> fixtures = List.of(
                new Fixture(SpecialCareCategory.MEDICAL, "先週の診断について相談がありました。", "診断"),
                new Fixture(SpecialCareCategory.CRIMINAL_RECORD, "前科に関する記載があります。", "前科"),
                new Fixture(SpecialCareCategory.SOCIAL_STATUS, "生活保護の情報が含まれています。", "生活保護"),
                new Fixture(SpecialCareCategory.DISABILITY, "車椅子の利用について記録されています。", "車椅子"),
                new Fixture(SpecialCareCategory.UNION, "労働組合の加入状況が書かれています。", "労働組合"),
                new Fixture(SpecialCareCategory.RELIGION, "宗教に関する情報が含まれています。", "宗教"),
                new Fixture(SpecialCareCategory.RACE_ETHNICITY, "出身民族についての記載があります。", "出身民族"));

        assertFixtures(fixtures);
    }

    @Test
    void benignTextIsNotExcluded() {
        SpecialCareTextScreen.ScreenVerdict verdict =
                SpecialCareTextScreen.screen("Quarterly renewal notes mention product training and sponsor changes.");

        assertFalse(verdict.excluded());
        assertEquals(Set.of(), verdict.categories());
    }

    private void assertFixtures(List<Fixture> fixtures) throws Exception {
        for (Fixture fixture : fixtures) {
            SpecialCareTextScreen.ScreenVerdict verdict = SpecialCareTextScreen.screen(fixture.text());
            assertTrue(verdict.excluded(), fixture.text());
            assertEquals(Set.of(fixture.category()), verdict.categories(), fixture.text());
            String serialized = objectMapper.writeValueAsString(verdict);
            assertFalse(serialized.toLowerCase(Locale.ROOT).contains(fixture.sensitiveSubstring().toLowerCase(Locale.ROOT)));
        }
    }

    private record Fixture(SpecialCareCategory category, String text, String sensitiveSubstring) {
    }
}
