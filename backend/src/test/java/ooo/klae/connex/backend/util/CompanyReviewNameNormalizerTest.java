package ooo.klae.connex.backend.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CompanyReviewNameNormalizerTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "株式会社さくら商事|さくら商事",
        "株式会社 さくら商事|さくら商事",
        "（株）さくら商事|さくら商事",
        "㈱さくら商事|さくら商事",
        "さくら商事株式会社|さくら商事",
        "有限会社さくら商事|さくら商事",
        "さくら商事（有）|さくら商事",
        "合同会社さくら商事|さくら商事",
        "（同）さくら商事|さくら商事",
        "Sakura Robotics Inc.|sakura robotics",
        "Ｓａｋｕｒａ　Ｒｏｂｏｔｉｃｓ　Ｉｎｃ．|sakura robotics",
        "Sakura Robotics, Incorporated|sakura robotics",
        "Sakura Robotics Co., Ltd.|sakura robotics",
        "Sakura Robotics K.K.|sakura robotics",
        "Sakura Robotics GmbH|sakura robotics",
        "Sakura Robotics Limited|sakura robotics",
        "Lincoln|lincoln",
        "Incognito|incognito",
        "Sakura Inc. Research|sakura inc. research",
        "株式会社研究所さくら|研究所さくら",
        "さくら株式会社研究所|さくら株式会社研究所"
    })
    void comparesBoundaryLegalFormsOnly(String raw, String expected) {
        assertEquals(Optional.of(expected), CompanyReviewNameNormalizer.normalize(raw));
        assertEquals(Optional.of(expected), CompanyReviewNameNormalizer.normalize(expected));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"株式会社", "(株)", "Inc.", "Co., Ltd.", "株式会社（株）", "\u0000さくら"})
    void rejectsMissingBaseNames(String raw) {
        assertTrue(CompanyReviewNameNormalizer.normalize(raw).isEmpty());
    }
}
