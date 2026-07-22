import { ReactRenderer } from "@tiptap/react";
import type {
    SuggestionOptions,
    SuggestionProps,
    SuggestionKeyDownProps,
} from "@tiptap/suggestion";
import type { ComponentType } from "react";

export type SuggestionListHandle = {
    onKeyDown: (props: { event: KeyboardEvent }) => boolean;
};

const MAX_HEIGHT = 288;
const GAP = 6;

/**
 * Build a Tiptap suggestion `render` handler that mounts a React list component
 * in a fixed-position portal anchored to the caret, flipping above the caret
 * when there is not enough room below. Shared by the mention and slash menus.
 */
export function createSuggestionRenderer<Item>(
    Component: ComponentType<{ items: Item[]; command: (item: Item) => void }>,
): NonNullable<SuggestionOptions<Item>["render"]> {
    return () => {
        let renderer: ReactRenderer<SuggestionListHandle> | null = null;
        let popup: HTMLDivElement | null = null;

        const place = (rect: DOMRect | null | undefined) => {
            if (!popup || !rect) return;
            const spaceBelow = window.innerHeight - rect.bottom;
            const openUp = spaceBelow < MAX_HEIGHT && rect.top > spaceBelow;
            popup.style.left = `${Math.round(rect.left)}px`;
            if (openUp) {
                popup.style.top = "auto";
                popup.style.bottom = `${Math.round(window.innerHeight - rect.top + GAP)}px`;
            } else {
                popup.style.bottom = "auto";
                popup.style.top = `${Math.round(rect.bottom + GAP)}px`;
            }
        };

        return {
            onStart: (props: SuggestionProps<Item>) => {
                renderer = new ReactRenderer(Component, {
                    props: { items: props.items, command: props.command },
                    editor: props.editor,
                });
                popup = document.createElement("div");
                popup.style.position = "fixed";
                popup.style.zIndex = "60";
                popup.style.pointerEvents = "auto";
                popup.setAttribute("data-slot", "editor-suggestion");
                popup.appendChild(renderer.element);
                document.body.appendChild(popup);
                place(props.clientRect?.());
            },
            onUpdate: (props: SuggestionProps<Item>) => {
                renderer?.updateProps({ items: props.items, command: props.command });
                place(props.clientRect?.());
            },
            onKeyDown: (props: SuggestionKeyDownProps) => {
                if (props.event.isComposing || props.event.keyCode === 229) return false;
                if (props.event.key === "Escape") return false;
                return renderer?.ref?.onKeyDown(props) ?? false;
            },
            onExit: () => {
                popup?.remove();
                popup = null;
                renderer?.destroy();
                renderer = null;
            },
        };
    };
}
