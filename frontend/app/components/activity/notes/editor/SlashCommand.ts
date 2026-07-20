import { Extension, type Editor } from "@tiptap/core";
import { PluginKey } from "@tiptap/pm/state";
import Suggestion from "@tiptap/suggestion";
import { createSuggestionRenderer } from "./suggestionRenderer";
import { SlashCommandList } from "./SlashCommandList";
import { filterSlashCommands, type SlashCommandItem } from "./slashCommands";

export interface SlashCommandOptions {
    commands: SlashCommandItem[];
}

/**
 * Mutable per-editor storage for the slash palette. Hosts assign
 * {@link SlashCommandStorage.onRunAction} after the editor mounts so registry
 * `run-action` commands always reach the latest callback without rebuilding
 * the extension.
 */
export interface SlashCommandStorage {
    onRunAction?: (actionId: string) => void;
}

/**
 * Assign the host callback that registry `run-action` slash commands invoke.
 * Call after the editor mounts (and on callback identity changes) so the
 * palette always dispatches to the latest handler.
 */
export function setSlashRunAction(
    editor: Editor,
    onRunAction: ((actionId: string) => void) | undefined,
): void {
    const storage = (editor.storage as { slashCommand?: SlashCommandStorage }).slashCommand;
    if (storage) storage.onRunAction = onRunAction;
}

/**
 * Notion-style `/` command palette. Reuses the shared suggestion renderer to
 * mount {@link SlashCommandList} at the caret; the trigger only fires at the
 * start of a block or after whitespace (never inside a word or a code block),
 * and selecting a command clears the typed `/query` before applying its block
 * transform.
 */
export const SlashCommand = Extension.create<SlashCommandOptions, SlashCommandStorage>({
    name: "slashCommand",

    addOptions() {
        return { commands: [] };
    },

    addStorage() {
        return { onRunAction: undefined };
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
