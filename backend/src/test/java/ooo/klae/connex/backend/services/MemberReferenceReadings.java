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
                "[Alice Former\\]](user:42)", "Alice Former\\]"));
        for (String title : List.of("\"`\"", "'`'", "(`)")) {
            readings.add(new Reading("b", "backtick title " + title,
                    "[m]: user:42 " + title + "\n\n[Alice Former][m]\n\nparagraph `", "Alice Former"));
        }
        readings.add(new Reading("b", "closing bracket inside link-text code",
                "[Alice `]` Former](user:42)", "Alice `]` Former"));
        readings.add(new Reading("canonical-code-span", "HTML type 1 then canonical link",
                "<script>\n` stray\n</script>\n[Alice `]` Former](user:42)", "Alice `]` Former"));
        readings.add(new Reading("canonical-escapes", "escaped tick before bracket-containing code",
                "<script>\n` stray\n</script>\n[Alice \\` `]` Former](user:42)", "Alice \\` `]` Former"));
        for (String escape : List.of("\\[", "\\]", "\\(", "\\)", "\\`")) {
            for (String label : List.of(escape + "Alice `]` Former", "Alice " + escape + " `]` Former",
                    "Alice `]` Former" + escape)) {
                readings.add(new Reading("canonical-escapes", "escaped delimiter " + label,
                        "<script>\n` stray\n</script>\n[" + label + "](user:42)", label));
            }
        }
        for (String label : List.of("Alice \\\\`]` Former", "Alice \\``]` Former", "Alice \\` \\] `]` Former")) {
            readings.add(new Reading("canonical-escapes", "slash parity and adjacent ticks " + label,
                    "<script>\n` stray\n</script>\n[" + label + "](user:42)", label));
        }
        readings.add(new Reading("sequential-code", "backslash does not escape a code closer",
                "[Alice `]\\` Former](user:42)", "Alice `]\\` Former"));
        commonMarkInlineExamples().forEach(example -> {
            String label = "Alice " + example.markdown() + " Former";
            readings.add(new Reading("commonmark-" + example.section(), "spec example " + example.number(),
                    "[" + label + "](user:42)", label));
        });
        for (String html : List.of("<script>\n` stray\n</script>", "<!--\n` stray\n-->", "<div>\n` stray\n</div>")) {
            for (String ticks : List.of("`", "``")) {
                for (String separator : List.of(":", "&colon;", "\\:")) {
                    String label = "Alice " + ticks + "]" + ticks + " Former &amp; Member";
                    readings.add(new Reading("canonical-code-span", "HTML then local code " + html + ticks + separator,
                            html + "\n[" + label + "](user" + separator + "42)", label));
                }
            }
        }
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
        readings.add(new Reading("final-1", "colon after prose shortcut",
                "Hello [Alice Former]: welcome.\n\n[Alice Former]: user:42", "Alice Former"));
        readings.add(new Reading("final-1", "space before colon is not a definition",
                "[Alice Former] : welcome.\n\n[Alice Former]: user:42", "Alice Former"));
        for (String marker : List.of("-", ">")) {
            readings.add(new Reading("final-2", "literal continuation marker " + marker,
                    "[Alice Former][m " + marker + " n]\n\n[m\n" + marker + " n]: user:42", "Alice Former"));
        }
        for (String prefix : List.of("> ", "> > ", "> - ", "- ", "1. ")) {
            readings.add(new Reading("final-2", "continued container definition key " + prefix,
                    "[Alice Former][m n]\n\n" + prefix + "[m\n" + prefix + "n]: user:42", "Alice Former"));
        }
        for (String boundary : List.of("\n\n", "\r\r", "\r\n\r\n", "\n# Heading\n", "\n> ", "\n- ")) {
            readings.add(new Reading("final-3", "code pairing stops at block " + boundary,
                    "` stray" + boundary + "[Alice `]` Former](user:42)", "Alice `]` Former"));
        }
        readings.add(new Reading("final-3", "soft line break inside code remains opaque",
                "[Alice `line\n]` Former](user:42)", "Alice `line\n]` Former"));
        readings.add(new Reading("final-3", "inline HTML does not interrupt code paragraph",
                "[Alice `line\n<em>]` Former](user:42)", "Alice `line\n<em>]` Former"));
        readings.add(new Reading("final-3", "quote continuation does not interrupt code paragraph",
                "> [Alice `line\n> ]` Former](user:42)", "Alice `line\n> ]` Former"));
        readings.add(new Reading("final-3", "lazy quote continuation preserves code pairing",
                "> [Alice `line\nlazy continuation\n> ]` Former](user:42)", "Alice `line\nlazy continuation\n> ]` Former"));
        for (String line : List.of("-=", "=-", " --== \t", "    ---", "**_", "_**", "\u2003")) {
            String label = "Alice `line\n" + line + "\n]` Former";
            readings.add(new Reading("boundary-regression", "non-block line " + line,
                    "[" + label + "](user:42)", label));
        }
        for (String line : List.of("---", "===", "   --- \t", "  === \t", "***", "___", "* * *", "- - -", "_ _ _",
                "> * * *", "> - - -", "> _ _ _")) {
            readings.add(new Reading("boundary-regression", "real block line " + line,
                    "` stray\n" + line + "\n[Alice `]` Former](user:42)", "Alice `]` Former"));
        }
        for (String remainder : List.of("welcome back.", "user:7 \"title\" trailing", "user:7 'title' trailing",
                "user:7 (title) trailing", "user:7 \"unterminated", "user:7 (unescaped (parenthesis))",
                "<user:7", "user:7)", "user:(7", "<user:7>suffix", "<user:<7>>", " ")) {
            readings.add(new Reading("definition-fallback", "invalid definition " + remainder,
                    "[Alice Former]: " + remainder + "\n\n[Alice Former]: user:42", "Alice Former"));
        }
        for (String prefix : List.of("Hello\n", "> Hello\n> ", "> Hello\n", "- Hello\n  ", "- Hello\n",
                "1. Hello\n   ", "1. Hello\n", "> - Hello\n>   ", "> - Hello\n", "Hello\n-=\n", "Hello\n=-\n",
                "===\n", "> Hello\n> -=\n> ", "Hello\n2. ", "- Hello\n2. ", "1. Hello\n   2. ", "1. First\n\nOutside paragraph\n2. ")) {
            readings.add(new Reading("paragraph-context", "definition cannot interrupt " + prefix,
                    prefix + "[Alice Former]: welcome.\n\n[Alice Former]: user:42", "Alice Former"));
        }
        readings.add(new Reading("paragraph-context", "consecutive definitions remain definitions",
                "[m]: user:42\n[n]: user:43\n\n[Alice Former][m] and [Pending Former][n]", "Alice Former"));
        readings.add(new Reading("paragraph-context", "blank line permits definition",
                "Hello\n\n[m]: user:42\n\n[Alice Former][m]", "Alice Former"));
        for (String label : List.of("Al&#93;ice", "Al&#91;ice", "Alice &amp; Bob")) {
            readings.add(new Reading("paragraph-context", "encoded reference key is a paragraph shortcut " + label,
                    "[" + label + "][member&#93;key]\n[member&#93;key]: user&#58;42", label));
        }
        readings.add(new Reading("paragraph-context", "encoded titled definition in paragraph",
                "[Alice &amp; Bob][m]\n[m]: user:42 \"a&#10;b\"\n"
                        + "[Pending &amp; Member][pending]\n[pending]: <user:43> (a&#13;b)", "Alice &amp; Bob"));
        paragraphInterruptions().stream().filter(reading -> !reading.definitionAllowed()).forEach(reading ->
                readings.add(new Reading("paragraph-interruption", reading.name(), reading.content(), "Alice Former")));
        return List.copyOf(readings);
    }

    /**
     * Inline-compatible witnesses from CommonMark 0.31.2
     * <a href="https://spec.commonmark.org/0.31.2/#backslash-escapes">§2.4</a> and
     * <a href="https://spec.commonmark.org/0.31.2/#code-spans">§6.1</a> (John MacFarlane).
     * Block-only examples and nested link/autolink examples are excluded because their constructs
     * cannot be wrapped unchanged in a member link label. Example 14 uses its compatible lines.
     */
    public static List<InlineExample> commonMarkInlineExamples() {
        return List.of(
                new InlineExample(12, "2.4", "\\!\\\"\\#\\$\\%\\&\\'\\(\\)\\*\\+\\,\\-\\.\\/\\:\\;\\<\\=\\>\\?\\@\\[\\\\\\]\\^\\_\\`\\{\\|\\}\\~"),
                new InlineExample(13, "2.4", "\\\t\\A\\a\\ \\3\\\u03c6\\\u00ab"),
                new InlineExample(14, "2.4", "\\*not emphasized*\n\\<br/> not a tag\n\\`not code`\n1\\. not a list\n\\* not a list\n\\# not a heading\n\\&ouml; not a character entity"),
                new InlineExample(15, "2.4", "\\\\*emphasis*"),
                new InlineExample(16, "2.4", "foo\\\nbar"),
                new InlineExample(17, "2.4", "`` \\[\\` ``"),
                new InlineExample(21, "2.4", "<a href=\"/bar\\/)\">"),
                new InlineExample(328, "6.1", "`foo`"),
                new InlineExample(329, "6.1", "`` foo ` bar ``"),
                new InlineExample(330, "6.1", "` `` `"),
                new InlineExample(331, "6.1", "`  ``  `"),
                new InlineExample(332, "6.1", "` a`"),
                new InlineExample(333, "6.1", "`\u00a0b\u00a0`"),
                new InlineExample(334, "6.1", "`\u00a0`\n`  `"),
                new InlineExample(335, "6.1", "``\nfoo\nbar  \nbaz\n``"),
                new InlineExample(336, "6.1", "``\nfoo \n``"),
                new InlineExample(337, "6.1", "`foo   bar \nbaz`"),
                new InlineExample(338, "6.1", "`foo\\`bar`"),
                new InlineExample(339, "6.1", "``foo`bar``"),
                new InlineExample(340, "6.1", "` foo `` bar `"),
                new InlineExample(341, "6.1", "*foo`*`"),
                new InlineExample(343, "6.1", "`<a href=\"`\">`"),
                new InlineExample(344, "6.1", "<a href=\"`\">`"),
                new InlineExample(345, "6.1", "`<https://foo.bar.`baz>`"),
                new InlineExample(347, "6.1", "```foo``"),
                new InlineExample(348, "6.1", "`foo"),
                new InlineExample(349, "6.1", "`foo``bar``"));
    }

    /** The spec example and its unchanged inline source, excluding its final document newline. */
    public record InlineExample(int number, String section, String markdown) {
    }

    /** Both sides of CommonMark's paragraph-interruption rules, including lazy containers. */
    public static List<ParagraphInterruption> paragraphInterruptions() {
        List<ParagraphInterruption> cases = new ArrayList<>();
        for (String prefix : List.of("Hello\n\n", "Hello\n# Heading\n", "Hello\n   ###### Heading\n",
                "Hello\n```java\n```\n", "Hello\n~~~\n~~~\n", "Hello\n***\n", "Hello\n- - -\n",
                "Hello\n___\n", "Hello\n> ", "Hello\n---\n", "Hello\n===\n", "Hello\n- ",
                "Hello\n* ", "Hello\n+ ", "Hello\n1. ", "Hello\n1) ", "Hello\n001. ",
                "Hello\n<script></script>\n", "Hello\n<!-- comment -->\n", "Hello\n<?instruction?>\n",
                "Hello\n<!DOCTYPE html>\n", "Hello\n<![CDATA[data]]>\n", "Hello\n<div></div>\n",
                "> Hello\n> # Heading\n> ", "> Hello\n> * ", "> Hello\n> 1. ")) {
            cases.add(new ParagraphInterruption("definition permitted after " + prefix,
                    prefix + "[Alice Former]: welcome.\n\n[Alice Former]: user:42", true));
        }
        for (String prefix : List.of("Hello\n* \n", "Hello\n+ \n", "Hello\n1. \n", "Hello\n1)\n",
                "Hello\n0. ", "Hello\n2. ", "Hello\n2) ", "Hello\n000. ",
                "Hello\n<custom></custom>\n", "Hello\n<custom attr=\"value\">\n", "Hello\n</custom>\n",
                "Hello\n    indented code\n", "Hello\n\tindented code\n", "Hello\n    # Heading\n",
                "Hello\n    > quote\n", "Hello\n    * item\n", "Hello\n####### Heading\n",
                "Hello\n#Heading\n", "Hello\n``java\n", "Hello\n```bad`info\n", "Hello\n~~\n",
                "Hello\n* _ *\n", "Hello\n-=\n", "Hello\n=-\n", "Hello\nparagraph continuation\n",
                "Hello\n* item\n", "Hello\n1. item\n", "Hello\n> paragraph\n", "> Hello\n> * \n> ",
                "> Hello\n* \n", "* Hello\n  * \n  ", "1. Hello\n   2. ", "Hello\n2. # Heading\n",
                "1. > Hello\n   > ", "* > Hello\n  > ", "> * > Hello\n>   > ",
                "> Hello\n>\t  # Heading\n> ")) {
            cases.add(new ParagraphInterruption("shortcut preserved after " + prefix,
                    prefix + "[Alice Former]: welcome.\n\n[Alice Former]: user:42", false));
        }
        return List.copyOf(cases);
    }

    /** A valid definition is suppressed only when its block can begin outside a paragraph. */
    public record ParagraphInterruption(String name, String content, boolean definitionAllowed) {
    }

    /** One recognition rule and its source-text witness. */
    public record Reading(String rule, String name, String content, String label) {
    }
}
