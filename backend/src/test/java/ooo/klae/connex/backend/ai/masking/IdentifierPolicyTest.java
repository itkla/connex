package ooo.klae.connex.backend.ai.masking;

import static ooo.klae.connex.backend.ai.masking.IdentifierPolicy.FieldKind.ADDRESS;
import static ooo.klae.connex.backend.ai.masking.IdentifierPolicy.FieldKind.AMOUNT;
import static ooo.klae.connex.backend.ai.masking.IdentifierPolicy.FieldKind.COMPANY_NAME;
import static ooo.klae.connex.backend.ai.masking.IdentifierPolicy.FieldKind.DATE;
import static ooo.klae.connex.backend.ai.masking.IdentifierPolicy.FieldKind.DAYS_SINCE_TOUCH;
import static ooo.klae.connex.backend.ai.masking.IdentifierPolicy.FieldKind.DEAL_STAGE_NAME;
import static ooo.klae.connex.backend.ai.masking.IdentifierPolicy.FieldKind.EMAIL;
import static ooo.klae.connex.backend.ai.masking.IdentifierPolicy.FieldKind.IMAGE_URL;
import static ooo.klae.connex.backend.ai.masking.IdentifierPolicy.FieldKind.LOGO_URL;
import static ooo.klae.connex.backend.ai.masking.IdentifierPolicy.FieldKind.PERSON_FULL_NAME;
import static ooo.klae.connex.backend.ai.masking.IdentifierPolicy.FieldKind.PHONE;
import static ooo.klae.connex.backend.ai.masking.IdentifierPolicy.FieldKind.RISK_FACTOR_CODE;
import static ooo.klae.connex.backend.ai.masking.IdentifierPolicy.FieldKind.ROLE;
import static ooo.klae.connex.backend.ai.masking.IdentifierPolicy.FieldKind.TITLE;
import static ooo.klae.connex.backend.ai.masking.IdentifierPolicy.FieldKind.TREND;
import static ooo.klae.connex.backend.ai.masking.IdentifierPolicy.FieldKind.WARMTH_BAND;
import static ooo.klae.connex.backend.ai.masking.IdentifierPolicy.FieldKind.WEBSITE;
import static ooo.klae.connex.backend.ai.masking.IdentifierPolicy.MaskMode.ALLOW;
import static ooo.klae.connex.backend.ai.masking.IdentifierPolicy.MaskMode.EXCLUDE;
import static ooo.klae.connex.backend.ai.masking.IdentifierPolicy.MaskMode.TOKENIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.List;

import org.junit.jupiter.api.Test;

class IdentifierPolicyTest {

    @Test
    void classificationModeFailsClosedAndHonorsSensitiveOptIn() {
        assertEquals(DataClassification.STANDARD, DataClassification.fromToken("standard"));
        assertEquals(DataClassification.SENSITIVE, DataClassification.fromToken("sensitive"));
        assertEquals(DataClassification.SPECIAL_CARE, DataClassification.fromToken("special_care"));
        assertEquals(DataClassification.SPECIAL_CARE, DataClassification.fromToken("unknown"));
        assertEquals(DataClassification.SPECIAL_CARE, DataClassification.fromToken(" "));

        assertEquals(ALLOW, IdentifierPolicy.classificationMode(DataClassification.STANDARD));
        assertEquals(EXCLUDE, IdentifierPolicy.classificationMode(DataClassification.SENSITIVE));
        assertEquals(ALLOW, IdentifierPolicy.classificationMode(DataClassification.SENSITIVE, true));
        assertEquals(EXCLUDE, IdentifierPolicy.classificationMode(DataClassification.SPECIAL_CARE, true));
        assertEquals(EXCLUDE, IdentifierPolicy.classificationMode(null, true));
        assertEquals(EXCLUDE, IdentifierPolicy.classificationMode(DataClassification.fromToken("unknown"), true));
    }

    @Test
    void fieldKindPolicyReturnsExpectedMaskModes() {
        assertEquals(TOKENIZE, IdentifierPolicy.fieldMode(PERSON_FULL_NAME));
        assertEquals(TOKENIZE, IdentifierPolicy.fieldMode(COMPANY_NAME));

        assertEquals(EXCLUDE, IdentifierPolicy.fieldMode(EMAIL));
        assertEquals(EXCLUDE, IdentifierPolicy.fieldMode(PHONE));
        assertEquals(EXCLUDE, IdentifierPolicy.fieldMode(ADDRESS));
        assertEquals(EXCLUDE, IdentifierPolicy.fieldMode(WEBSITE));
        assertEquals(EXCLUDE, IdentifierPolicy.fieldMode(IMAGE_URL));
        assertEquals(EXCLUDE, IdentifierPolicy.fieldMode(LOGO_URL));
        assertEquals(EXCLUDE, IdentifierPolicy.fieldMode(null));

        assertEquals(ALLOW, IdentifierPolicy.fieldMode(WARMTH_BAND));
        assertEquals(ALLOW, IdentifierPolicy.fieldMode(TREND));
        assertEquals(ALLOW, IdentifierPolicy.fieldMode(DAYS_SINCE_TOUCH));
        assertEquals(ALLOW, IdentifierPolicy.fieldMode(RISK_FACTOR_CODE));
        assertEquals(ALLOW, IdentifierPolicy.fieldMode(DEAL_STAGE_NAME));
        assertEquals(ALLOW, IdentifierPolicy.fieldMode(AMOUNT));
        assertEquals(ALLOW, IdentifierPolicy.fieldMode(DATE));
        assertEquals(ALLOW, IdentifierPolicy.fieldMode(TITLE));
        assertEquals(ALLOW, IdentifierPolicy.fieldMode(ROLE));
    }

    @Test
    void maskedPromptConstructorIsPackagePrivate() throws Exception {
        Constructor<MaskedPrompt> constructor = MaskedPrompt.class.getDeclaredConstructor(String.class, List.class);
        int modifiers = constructor.getModifiers();

        assertFalse(Modifier.isPublic(modifiers));
        assertFalse(Modifier.isPrivate(modifiers));
        assertFalse(Modifier.isProtected(modifiers));
    }
}
