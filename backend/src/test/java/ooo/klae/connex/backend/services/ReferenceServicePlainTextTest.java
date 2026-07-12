package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ReferenceServicePlainTextTest {

    @Test
    void toPlainText_redactsReferenceStyleNoteLinksAndDefinitions() {
        String content = "Discuss [Secret][n], [Collapsed][], and [Shortcut].\n\n"
            + "[n]: note:123 \"title\"\n"
            + "[Collapsed]: <note:124>\n"
            + "[Shortcut]: note:125";

        String plainText = ReferenceService.toPlainText(content);

        assertEquals("a note", plainText);
    }

    @Test
    void toPlainText_preservesOrdinaryReferenceStyleLinks() {
        String content = "Read [Docs][guide] or [Guide].\n\n"
            + "[guide]: https://example.com/docs\n"
            + "[Guide]: /guide";

        assertEquals(content, ReferenceService.toPlainText(content));
    }

    @Test
    void toPlainText_conservativelyRedactsAnyReferenceDefinitionWithANoteTarget() {
        String content = "Read [Docs][guide].\n\n"
            + "[guide]: https://example.com/docs\n"
            + "[guide]: note:123";

        String plainText = ReferenceService.toPlainText(content);

        assertEquals("a note", plainText);
    }

    @Test
    void toPlainText_redactsMultilineEscapedAndImageReferenceVariants() {
        assertEquals("a note", ReferenceService.toPlainText(
            "[Secret][n]\n\n[n]:\n  note:123"));
        assertEquals("a note", ReferenceService.toPlainText(
            "[Secret \\] Note][n]\n\n[n]: note:123"));
        assertEquals("a note", ReferenceService.toPlainText(
            "![Secret][n]\n\n[n]: note:123"));
    }

    @Test
    void toPlainText_redactsEscapedAndEntityEncodedNoteTargets() {
        assertEquals("a note", ReferenceService.toPlainText(
            "[Secret][n]\n\n[n]: note\\:123"));
        assertEquals("a note", ReferenceService.toPlainText(
            "[Secret][n]\n\n[n]: note&#58;123"));
        assertEquals("a note", ReferenceService.toPlainText(
            "[Secret][n]\n\n[n]: note:&#49;23"));
        assertEquals("a note", ReferenceService.toPlainText(
            "[Secret][n]\n\n[n]: note&colon;123"));
    }

    @Test
    void toPlainText_preservesNoteLikeProseOutsideMarkdownDestinations() {
        String content = "Ship release-note:123 after reviewing note:456 in plain prose";

        assertEquals(content, ReferenceService.toPlainText(content));
    }

    @Test
    void toPlainText_redactsOversizedNoteDestinationIds() {
        assertEquals("a note", ReferenceService.toPlainText(
            "[Invalid](note:999999999999999999999999999999999999)"));
    }

    @Test
    void toPlainText_redactsEmptyLabelNoteDestinations() {
        assertEquals("a note", ReferenceService.toPlainText("[](note:123)"));
    }

    @Test
    void toPlainText_redactsNoteDestinationsAfterBalancedNestedLabels() {
        assertEquals("a note", ReferenceService.toPlainText(
            "[Roadmap [private appendix]](note:123)"));
        assertEquals("a note", ReferenceService.toPlainText(
            "![Diagram [private appendix]](<note:456> \"preview\")"));
    }

    @Test
    void toPlainText_redactsNoteDestinationsAfterCodeSpanClosingBrackets() {
        assertEquals("a note", ReferenceService.toPlainText(
            "[Secret `]` appendix](note:123)"));
        assertEquals("a note", ReferenceService.toPlainText(
            "[Secret ``]` [appendix]`` details](note:456)"));
    }

    @Test
    void toPlainText_preservesCodeSpanBracketsNearNoteLikeProse() {
        String content = "Use `]` in prose before reviewing note:123";

        assertEquals(content, ReferenceService.toPlainText(content));
    }

    @Test
    void toPlainText_scansHostileUnmatchedLabelsInLinearTime() {
        String noteLink = "[Secret](note:123)";
        String content = "[".repeat(50_000 - noteLink.length()) + noteLink;

        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
            assertEquals("a note", ReferenceService.toPlainText(content)));
    }
}
