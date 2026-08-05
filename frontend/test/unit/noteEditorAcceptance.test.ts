import { Editor, type JSONContent } from "@tiptap/core";
import { inputRegex } from "@tiptap/extension-image";
import StarterKit from "@tiptap/starter-kit";
import { TaskItem } from "@tiptap/extension-task-item";
import { TaskList } from "@tiptap/extension-task-list";
import MarkdownIt from "markdown-it";
import { describe, expect, it } from "vitest";
import { Markdown } from "tiptap-markdown";

import {
    createMarkdownImageExtension,
    markdownImageInputAttributes,
} from "@/app/components/activity/notes/editor/MarkdownImage";
import { createMarkdownTableExtensions } from "@/app/components/activity/notes/editor/MarkdownTable";
import { Mention, encodeMentions, insertMention } from "@/app/components/activity/notes/editor/Mention";
import { NoteText, NoteUnderline } from "@/app/components/activity/notes/editor/NoteUnderline";
import { NOTE_EDITOR_MARKDOWN_OPTIONS } from "@/app/components/activity/notes/editor/noteEditorMarkdown";
import { FileReference } from "@/app/components/activity/notes/editor/FileReference";
import { buildSlashCommands, filterSlashCommands } from "@/app/components/activity/notes/editor/slashCommands";
import { isSuggestionCompositionEvent } from "@/app/components/activity/notes/editor/suggestionRenderer";

function createEditor(content?: JSONContent, withMention = false): Editor {
    return new Editor({
        extensions: [
            StarterKit.configure({ text: false, underline: false }),
            TaskList,
            TaskItem.configure({ nested: true }),
            ...createMarkdownTableExtensions(),
            createMarkdownImageExtension("Image unavailable"),
            NoteText,
            NoteUnderline,
            ...(withMention ? [Mention] : []),
            Markdown.configure(NOTE_EDITOR_MARKDOWN_OPTIONS),
        ],
        content: content ?? { type: "doc", content: [{ type: "paragraph" }] },
    });
}

function readMarkdown(editor: Editor): string {
    const storage = editor.storage as { markdown?: { getMarkdown?: () => string } };
    return storage.markdown?.getMarkdown?.() ?? "";
}

function matchImageInput(value: string): RegExpMatchArray {
    const match = inputRegex.exec(value);
    if (!match) throw new Error("Expected Markdown image input to match");
    return match;
}

describe("note paste Markdown matrix", () => {
    it("keeps the editor on the sanitized Markdown paste contract", () => {
        expect(NOTE_EDITOR_MARKDOWN_OPTIONS).toMatchObject({
            html: false,
            transformPastedText: true,
            transformCopiedText: true,
            linkify: false,
        });
    });

    it("parses pasted-style lists and tables while rejecting raw HTML", () => {
        const markdown = new MarkdownIt({ html: false, breaks: true, linkify: false });
        const html = markdown.render(
            [
                "- Alpha",
                "- Beta",
                "",
                "| Owner | State |",
                "| --- | --- |",
                "| Mina | Ready |",
                "",
                "<script>alert('xss')</script>",
                "",
                "Safe paragraph",
            ].join("\n"),
        );

        expect(html).toContain("<ul>");
        expect(html).toContain("<table>");
        expect(html).toContain("Safe paragraph");
        expect(html).not.toContain("<script>");
        expect(html).toContain("&lt;script&gt;");
    });

    it("requires alt text for Markdown image embeds on the paste/input path", () => {
        expect(markdownImageInputAttributes(matchImageInput("![Product shot](https://example.com/shot.png)"))).toEqual({
            src: "https://example.com/shot.png",
            alt: "Product shot",
            title: undefined,
        });
        expect(markdownImageInputAttributes(matchImageInput("![](https://example.com/missing-alt.png)"))).toBeNull();
        expect(markdownImageInputAttributes(matchImageInput("![  ](https://example.com/blank-alt.png)"))).toBeNull();
    });

    it("round-trips list and table structure through the editor serializer", () => {
        const editor = createEditor({
            type: "doc",
            content: [
                {
                    type: "bulletList",
                    content: [
                        { type: "listItem", content: [{ type: "paragraph", content: [{ type: "text", text: "Alpha" }] }] },
                        { type: "listItem", content: [{ type: "paragraph", content: [{ type: "text", text: "Beta" }] }] },
                    ],
                },
                {
                    type: "table",
                    content: [
                        {
                            type: "tableRow",
                            content: [
                                { type: "tableHeader", content: [{ type: "paragraph", content: [{ type: "text", text: "Owner" }] }] },
                                { type: "tableHeader", content: [{ type: "paragraph", content: [{ type: "text", text: "State" }] }] },
                            ],
                        },
                        {
                            type: "tableRow",
                            content: [
                                { type: "tableCell", content: [{ type: "paragraph", content: [{ type: "text", text: "Mina" }] }] },
                                { type: "tableCell", content: [{ type: "paragraph", content: [{ type: "text", text: "Ready" }] }] },
                            ],
                        },
                    ],
                },
            ],
        });

        const markdown = readMarkdown(editor);
        expect(markdown).toContain("Alpha");
        expect(markdown).toContain("Beta");
        expect(markdown).toContain("| Owner | State |");
        expect(markdown).toContain("| Mina | Ready |");
        editor.destroy();
    });
});

describe("note file reference embed", () => {
    it("serializes inserted file mentions as canonical attachment references", () => {
        const editor = createEditor(undefined, true);
        insertMention(editor, { refType: "file", refId: 42, label: "Q3 deck.pdf" });
        expect(readMarkdown(editor)).toContain("[Q3 deck.pdf](file:42)");
        editor.destroy();
    });

    it("encodes file reference tokens for Markdown load without inventing a second format", () => {
        const { text, mentions } = encodeMentions("See [Q3 deck.pdf](file:42) today.");
        expect(mentions).toEqual([{ refType: "file", refId: 42, label: "Q3 deck.pdf" }]);
        expect(text).not.toContain("file:42");
        expect(text).toContain("\uE0000\uE001");
    });

    it("exposes a slash file command that opens the reference picker", () => {
        const editor = new Editor({
            extensions: [
                StarterKit.configure({ text: false, underline: false }),
                NoteText,
                NoteUnderline,
                FileReference,
                Markdown.configure(NOTE_EDITOR_MARKDOWN_OPTIONS),
            ],
            content: { type: "doc", content: [{ type: "paragraph" }] },
        });
        const commands = buildSlashCommands((key) => key);
        const file = filterSlashCommands(commands, "file").find((command) => command.id === "file");
        expect(file).toBeDefined();
        file?.run(editor, { from: 1, to: 1 });
        const storage = editor.storage as { fileReference?: { openRequest?: number } };
        expect(storage.fileReference?.openRequest).toBe(1);
        editor.destroy();
    });
});

describe("note suggestion IME guard", () => {
    it("treats composition and keyCode 229 as non-committing suggestion events", () => {
        expect(isSuggestionCompositionEvent({ isComposing: true, keyCode: 13 })).toBe(true);
        expect(isSuggestionCompositionEvent({ isComposing: false, keyCode: 229 })).toBe(true);
        expect(isSuggestionCompositionEvent({ isComposing: false, keyCode: 13 })).toBe(false);
    });
});
