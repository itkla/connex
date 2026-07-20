import { Extension } from "@tiptap/core";
import { PluginKey } from "@tiptap/pm/state";
import Suggestion from "@tiptap/suggestion";
import { createSuggestionRenderer } from "./suggestionRenderer";
import { SlashCommandList } from "./SlashCommandList";
import { filterSlashCommands, type SlashCommandItem } from "./slashCommands";

export interface SlashCommandOptions {
    commands: SlashCommandItem[];
}

/**
 * Notion-style `/` command palette. Reuses the shared suggestion renderer to
 * mount {@link SlashCommandList} at the caret; the trigger only fires at the
 * start of a block or after whitespace (never inside a word or a code block),
 * and selecting a command clears the typed `/query` before applying its block
 * transform.
 */
export const SlashCommand = Extension.create<SlashCommandOptions>({
    name: "slashCommand",

    addOptions() {
        return { commands: [] };
    },

    addProseMirrorPlugins() {
        const commands = this.options.commands;
        return [
            Suggestion<SlashCommandItem>({
                editor: this.editor,
                char: "/",
                pluginKey: new PluginKey("note-slash-command"),
                allowSpaces: false,
                allow: ({ state, range }) => {
                    const $from = state.doc.resolve(range.from);
                    if ($from.parent.type.spec.code) return false;
                    const textBefore = $from.parent.textBetween(0, $from.parentOffset, undefined, " ");
                    return textBefore.length === 0 || /\s$/.test(textBefore);
                },
                items: ({ query }) => filterSlashCommands(commands, query),
                command: ({ editor, range, props }) => {
                    props.run(editor, range);
                },
                render: createSuggestionRenderer(SlashCommandList),
            }),
        ];
    },
});
