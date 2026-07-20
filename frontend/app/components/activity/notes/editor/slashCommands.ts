import type { ComponentType } from "react";
import type { Editor, Range } from "@tiptap/core";
import {
    ChevronRight,
    Heading1,
    Heading2,
    Heading3,
    Info,
    List,
    ListOrdered,
    ListTodo,
    Minus,
    SquareCode,
    TextQuote,
    Type,
} from "lucide-react";
import {
    ENTITY_COMMANDS,
    TASK_COMMAND,
    type SlashCommandDef,
} from "../commands/slashCommandRegistry";
import type { SlashCommandStorage } from "./SlashCommand";

/** Icon component renderable by the slash menu; fits both Lucide and Heroicons glyphs. */
export type SlashCommandIcon = ComponentType<{ className?: string }>;

export type SlashCommandItem = {
    id: string;
    title: string;
    subtitle: string;
    keywords: string[];
    icon: SlashCommandIcon;
    run: (editor: Editor, range: Range) => void;
};

type Translate = (key: string) => string;

/**
 * Build the slash-command registry with translated labels. Selecting a command
 * clears the typed `/query` range, then applies its block transform to the
 * current line — mirroring the toolbar's command chains.
 */
export function buildSlashCommands(t: Translate): SlashCommandItem[] {
    return [
        {
            id: "text",
            title: t("slashText"),
            subtitle: t("slashTextHint"),
            keywords: ["text", "paragraph", "plain", "body"],
            icon: Type,
            run: (editor, range) => editor.chain().focus().deleteRange(range).setParagraph().run(),
        },
        {
            id: "heading1",
            title: t("heading1"),
            subtitle: t("slashH1Hint"),
            keywords: ["h1", "heading", "title", "large"],
            icon: Heading1,
            run: (editor, range) =>
                editor.chain().focus().deleteRange(range).toggleHeading({ level: 1 }).run(),
        },
        {
            id: "heading2",
            title: t("heading2"),
            subtitle: t("slashH2Hint"),
            keywords: ["h2", "heading", "subtitle", "medium"],
            icon: Heading2,
            run: (editor, range) =>
                editor.chain().focus().deleteRange(range).toggleHeading({ level: 2 }).run(),
        },
        {
            id: "heading3",
            title: t("heading3"),
            subtitle: t("slashH3Hint"),
            keywords: ["h3", "heading", "small"],
            icon: Heading3,
            run: (editor, range) =>
                editor.chain().focus().deleteRange(range).toggleHeading({ level: 3 }).run(),
        },
        {
            id: "bulletList",
            title: t("bulletList"),
            subtitle: t("slashBulletHint"),
            keywords: ["bullet", "list", "unordered", "ul"],
            icon: List,
            run: (editor, range) =>
                editor.chain().focus().deleteRange(range).toggleBulletList().run(),
        },
        {
            id: "orderedList",
            title: t("orderedList"),
            subtitle: t("slashOrderedHint"),
            keywords: ["ordered", "numbered", "list", "ol"],
            icon: ListOrdered,
            run: (editor, range) =>
                editor.chain().focus().deleteRange(range).toggleOrderedList().run(),
        },
        {
            id: "taskList",
            title: t("taskList"),
            subtitle: t("slashTaskHint"),
            keywords: ["task", "todo", "checkbox", "checklist"],
            icon: ListTodo,
            run: (editor, range) =>
                editor.chain().focus().deleteRange(range).toggleTaskList().run(),
        },
        {
            id: "blockquote",
            title: t("blockquote"),
            subtitle: t("slashQuoteHint"),
            keywords: ["quote", "blockquote", "citation"],
            icon: TextQuote,
            run: (editor, range) =>
                editor.chain().focus().deleteRange(range).toggleBlockquote().run(),
        },
        {
            id: "codeBlock",
            title: t("codeBlock"),
            subtitle: t("slashCodeHint"),
            keywords: ["code", "codeblock", "snippet", "pre"],
            icon: SquareCode,
            run: (editor, range) =>
                editor.chain().focus().deleteRange(range).toggleCodeBlock().run(),
        },
        {
            id: "divider",
            title: t("slashDivider"),
            subtitle: t("slashDividerHint"),
            keywords: ["divider", "hr", "rule", "separator", "line"],
            icon: Minus,
            run: (editor, range) =>
                editor.chain().focus().deleteRange(range).setHorizontalRule().run(),
        },
        {
            id: "callout",
            title: t("slashCallout"),
            subtitle: t("slashCalloutHint"),
            keywords: ["callout", "note", "info", "highlight", "aside", "warning"],
            icon: Info,
            run: (editor, range) =>
                editor
                    .chain()
                    .focus()
                    .deleteRange(range)
                    .insertContent({
                        type: "callout",
                        attrs: { variant: "info" },
                        content: [{ type: "paragraph" }],
                    })
                    .run(),
        },
        {
            id: "toggle",
            title: t("slashToggle"),
            subtitle: t("slashToggleHint"),
            keywords: ["toggle", "collapse", "details", "accordion", "expand", "fold"],
            icon: ChevronRight,
            run: (editor, range) =>
                editor
                    .chain()
                    .focus()
                    .deleteRange(range)
                    .insertContent({
                        type: "toggle",
                        content: [{ type: "toggleSummary" }, { type: "paragraph" }],
                    })
                    .run(),
        },
    ];
}

function referenceTrigger(def: SlashCommandDef): "@" | "#" {
    return def.entityTypes?.some((type) => type === "user" || type === "person") ? "@" : "#";
}

function editorRunAction(editor: Editor): ((actionId: string) => void) | undefined {
    const storage = (editor.storage as { slashCommand?: SlashCommandStorage }).slashCommand;
    return storage?.onRunAction;
}

/**
 * Map the shared registry commands into the Tiptap {@link SlashCommandItem} shape.
 * `insert-reference` commands clear the typed `/query` and insert the matching
 * mention trigger character, handing off to the existing @/# suggestion flow;
 * `run-action` commands clear the range and notify the host through the
 * {@link SlashCommandStorage.onRunAction} callback assigned after mount. The
 * task action is only offered when `includeActions` is set.
 */
export function buildRegistrySlashCommands(t: Translate, includeActions = false): SlashCommandItem[] {
    const defs: SlashCommandDef[] = [...ENTITY_COMMANDS, ...(includeActions ? [TASK_COMMAND] : [])];
    return defs.flatMap((def): SlashCommandItem[] => {
        if (def.kind === "insert-text") return [];
        const run =
            def.kind === "insert-reference"
                ? (editor: Editor, range: Range) =>
                      editor.chain().focus().deleteRange(range).insertContent(referenceTrigger(def)).run()
                : (editor: Editor, range: Range) => {
                      editor.chain().focus().deleteRange(range).run();
                      if (def.actionId) editorRunAction(editor)?.(def.actionId);
                  };
        return [
            {
                id: def.id,
                title: t(def.labelKey),
                subtitle: t(def.subtitleKey),
                keywords: [...def.aliases],
                icon: def.icon,
                run,
            },
        ];
    });
}

/**
 * Filter slash commands by a query against each command's title and keywords.
 */
export function filterSlashCommands(commands: SlashCommandItem[], query: string): SlashCommandItem[] {
    const needle = query.trim().toLowerCase();
    if (!needle) return commands;
    return commands.filter(
        (command) =>
            command.title.toLowerCase().includes(needle) ||
            command.keywords.some((keyword) => keyword.includes(needle)),
    );
}
