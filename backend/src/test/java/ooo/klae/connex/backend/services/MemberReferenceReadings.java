package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.List;

/** Shared adversarial readings for the assistant's conservative member-label collector. */
public final class MemberReferenceReadings {
    private MemberReferenceReadings() {
    }

    /** Supplies original Markdown and the member label each reading must retain. */
    public static List<Reading> all() {
        List<Reading> readings = new ArrayList<>();
        for (String separator : List.of(":", "&colon;", "\\:")) {
            readings.add(new Reading("a", "independent canonical " + separator,
                    "[Known](user:42)\n\n[ignored] [Alice Former](user" + separator + "42)", "Alice Former"));
        }
        readings.add(new Reading("a", "canonical with escaped closing delimiter",
                "[Alice Former\\](user:42)", "Alice Former\\"));
        for (String title : List.of("\"`\"", "'`'", "(`)")) {
            readings.add(new Reading("b", "backtick title " + title,
                    "[m]: user:42 " + title + "\n\n[Alice Former][m]\n\nparagraph `", "Alice Former"));
        }
        readings.add(new Reading("b", "closing bracket inside link-text code",
                "[Alice `]` Former](user:42)", "Alice `]` Former"));
        for (String opaque : List.of("<!-- ] -->", "<span title=\"]\">", "<span title=']'>",
                "<span title=]>", "<? ] ?>", "<!DECL ]>", "<![CDATA[ ] ]]>", "<x attr=\"`]\">")) {
            String label = "Alice " + opaque + " Former";
            readings.add(new Reading("b", "opaque link text " + opaque,
                    "[" + label + "](user:42)", label));
        }
        for (String unclosed : List.of("<!--", "<?", "<!DECL", "<![CDATA[", "<span title=\"")) {
            readings.add(new Reading("b", "unclosed opaque construct " + unclosed,
                    unclosed + "\n\n[Alice Former][m]\n\n[m]: user:42", "Alice Former"));
        }
        for (String newline : List.of("\n", "\r", "\r\n")) {
            for (String destination : List.of("user:42", "<user:42>", "user\\:42", "<user\\:42>")) {
                readings.add(new Reading("c", "unindented continuation " + destination + newline,
                        "[Alice Former][m]\n\n[m]:" + newline + destination, "Alice Former"));
            }
        }
        for (String prefix : List.of("> ", "> > ", "- ", "* ", "+ ", "1. ", "2) ", "> - ")) {
            readings.add(new Reading("c", "container " + prefix,
                    "[Alice Former][m]\n\n" + prefix + "[m]: <user:42>", "Alice Former"));
            readings.add(new Reading("c", "container continuation " + prefix,
                    "[Alice Former][m]\n\n" + prefix + "[m]:\r" + prefix + "<user\\:42>", "Alice Former"));
        }
        readings.add(new Reading("c", "unmatched bracket before definition",
                "[\n\n[m]: user:42\n\n[Alice Former][m]", "Alice Former"));
        readings.add(new Reading("c", "unmatched bracket before container definition",
                "[\n\n> [m]: <user:42>\n\n[Alice Former][m]", "Alice Former"));
        readings.add(new Reading("d", "shortcut after failed inline destination",
                "[Alice Former](not a link)\n\n[Alice Former]: user:42", "Alice Former"));
        readings.add(new Reading("d", "nested link leaves an independent shortcut",
                "[prefix [Other](person:1)][Alice Former]\n\n[Alice Former]: user:42", "Alice Former"));
        readings.add(new Reading("d", "paragraph break leaves an independent shortcut",
                "[ignored\n\nparagraph][Alice Former]\n\n[Alice Former]: user:42", "Alice Former"));
        readings.add(new Reading("e", "Unicode case folding",
                "[Alice Former][SS]\n\n[ß]: user:42", "Alice Former"));
        readings.add(new Reading("e", "Unicode whitespace collapse",
                "[Alice Former][ ONE\t TWO ]\n\n[one\u00a0two]: user:42", "Alice Former"));
        return List.copyOf(readings);
    }

    /** One recognition rule and its source-text witness. */
    public record Reading(String rule, String name, String content, String label) {
    }
}
