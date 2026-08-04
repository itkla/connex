import { Editor, type JSONContent } from "@tiptap/core";
import { inputRegex } from "@tiptap/extension-image";
import StarterKit from "@tiptap/starter-kit";
import { describe, expect, it } from "vitest";
import { Markdown } from "tiptap-markdown";

import {
    createMarkdownImageExtension,
    createMarkdownImageInputRule,
    markdownImageInputAttributes,
    normalizeNoteImageSource,
} from "@/app/components/activity/notes/editor/MarkdownImage";

function createEditor(content?: JSONContent): Editor {
    return new Editor({
        extensions: [
            StarterKit,
            createMarkdownImageExtension("Image unavailable"),
            Markdown.configure({ html: false, transformPastedText: true }),
        ],
        content: content ?? { type: "doc", content: [{ type: "paragraph" }] },
    });
}

function readMarkdown(editor: Editor): string {
    const editorStorage: unknown = editor.storage;
    if (!editorStorage || typeof editorStorage !== "object" || !("markdown" in editorStorage)) return "";
    const markdownStorage = editorStorage.markdown;
    if (!markdownStorage || typeof markdownStorage !== "object" || !("getMarkdown" in markdownStorage)) return "";
    const getMarkdown = markdownStorage.getMarkdown;
    return typeof getMarkdown === "function" ? getMarkdown.call(markdownStorage) : "";
}

function imageSources(editor: Editor): string[] {
    const sources: string[] = [];
    editor.state.doc.descendants((node) => {
        if (node.type.name !== "image") return;
        const source: unknown = node.attrs.src;
        if (typeof source === "string") sources.push(source);
    });
    return sources;
}

function matchImageInput(value: string): RegExpMatchArray {
    const match = inputRegex.exec(value);
    if (!match) throw new Error("Expected Markdown image input to match");
    return match;
}

function createTypedInputEditor(value: string): Editor {
    return createEditor({
        type: "doc",
        content: [{
            type: "paragraph",
            content: [{ type: "text", text: value.slice(0, -1) }],
        }],
    });
}

function runImageInputRule(editor: Editor, value: string) {
    const transaction = editor.state.tr;
    Object.defineProperty(editor.state, "tr", { get: () => transaction });
    const rule = createMarkdownImageInputRule(editor.schema.nodes.image);
    const result = rule.handler({
        state: editor.state,
        range: { from: 1, to: value.length },
        match: matchImageInput(value),
        commands: editor.commands,
        chain: () => editor.chain(),
        can: () => editor.can(),
    });
    return { result, transaction };
}

describe("Markdown note images", () => {
    it.each([
        ["https://example.com/image.png", "https://example.com/image.png"],
        ["  https://example.com/a%20b.png  ", "https://example.com/a%20b.png"],
        ["/api/attachments/content/123e4567-e89b-42d3-a456-426614174000.png", "/api/attachments/content/123e4567-e89b-42d3-a456-426614174000.png"],
    ])("accepts safe persisted sources", (value, expected) => {
        expect(normalizeNoteImageSource(value)).toBe(expected);
    });

    it.each([
        "http://example.com/image.png",
        "javascript:alert(1)",
        "data:image/png;base64,AAAA",
        "blob:https://example.com/id",
        "//example.com/image.png",
        "https://user:secret@example.com/image.png",
        "/image\\name.png",
    ])("rejects unsafe persisted sources", (value) => {
        expect(normalizeNoteImageSource(value)).toBeNull();
    });

    it.each([
        "![Unsafe](javascript:alert(1))",
        "![](https://example.com/image.png)",
        "![  ](https://example.com/image.png)",
    ])("leaves rejected typed image input unchanged", (value) => {
        expect(markdownImageInputAttributes(matchImageInput(value))).toBeNull();
        const editor = createTypedInputEditor(value);
        const before = editor.state.doc.toJSON();
        const { result, transaction } = runImageInputRule(editor, value);
        expect(result).toBeNull();
        expect(transaction.steps).toHaveLength(0);
        expect(transaction.doc.toJSON()).toEqual(before);
        editor.destroy();
    });

    it("replaces valid typed Markdown with an image node", () => {
        const value = "![Diagram](https://example.com/image.png)";
        expect(markdownImageInputAttributes(matchImageInput("![Diagram](https://example.com/image.png)"))).toEqual({
            src: "https://example.com/image.png",
            alt: "Diagram",
            title: undefined,
        });
        const editor = createTypedInputEditor(value);
        const { result, transaction } = runImageInputRule(editor, value);
        expect(result).toBeUndefined();
        expect(transaction.steps.length).toBeGreaterThan(0);
        expect(transaction.doc.textContent).toBe("");
        const sources: string[] = [];
        transaction.doc.descendants((node) => {
            if (node.type.name === "image" && typeof node.attrs.src === "string") sources.push(node.attrs.src);
        });
        expect(sources).toEqual(["https://example.com/image.png"]);
        editor.destroy();
    });

    it("inserts and preserves an accessible Markdown image", () => {
        const editor = createEditor();

        expect(editor.commands.setImage({
            src: "https://example.com/diagram.png",
            alt: "Quarterly pipeline diagram",
        })).toBe(true);
        expect(readMarkdown(editor)).toBe(
            "![Quarterly pipeline diagram](https://example.com/diagram.png)",
        );

        const restored = createEditor(editor.getJSON());
        expect(imageSources(restored)).toEqual(["https://example.com/diagram.png"]);
        expect(readMarkdown(restored)).toBe(readMarkdown(editor));
        restored.destroy();
        editor.destroy();
    });

    it("refuses unsafe command sources", () => {
        const editor = createEditor();
        expect(editor.commands.setImage({ src: "javascript:alert(1)", alt: "Unsafe" })).toBe(false);
        expect(editor.commands.setImage({ src: "https://example.com/image.png", alt: "  " })).toBe(false);
        expect(imageSources(editor)).toEqual([]);

        editor.destroy();
    });
});
