package ooo.klae.connex.backend.ai.masking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AiGeneratedContentScreenTest {

    @Test
    void rejectsDirectContactDetailsAndRawRecordIds() {
        assertEquals(
                "email_address",
                AiGeneratedContentScreen.rejectionReason("Contact ada@example.com"));
        assertEquals(
                "phone_number",
                AiGeneratedContentScreen.rejectionReason("Call +1 (415) 555-0100"));
        assertEquals(
                "raw_record_id",
                AiGeneratedContentScreen.rejectionReason("person id: 42"));
    }

    @Test
    void acceptsOrdinaryGeneratedSummaryContent() {
        assertNull(AiGeneratedContentScreen.rejectionReason(
                "The user prefers quarterly planning and has an unresolved renewal question."));
    }

    @Test
    void distinguishesBracedFromBarePlaceholderBodies() {
        assertFalse(AiGeneratedContentScreen.containsBarePlaceholder(
                "Compare {{P1}} with {{D2}}."));
        assertTrue(AiGeneratedContentScreen.containsBarePlaceholder(
                "Compare P1 with the renewal."));
        assertTrue(AiGeneratedContentScreen.containsPlaceholder(
                "Compare {{P1}} with the renewal."));
    }
}
