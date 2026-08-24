import { act, type ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
    installInteractiveDocument,
    type InteractiveElement,
} from "@/test/unit/helpers/interactiveDocument";

/**
 * The menu, modelled on the one constraint that matters here: its content is portalled and
 * unmounted the moment the menu closes, and selecting an item closes the menu unless that item
 * prevents the default. Anything rendered inside the content is therefore destroyed by the very
 * selection meant to open it. The real Radix menu behaves this way; reproducing it is what lets a
 * node-environment test catch the bug at all.
 */
vi.mock("@/components/ui/dropdown-menu", async () => {
    const React = await import("react");
    const MenuContext = React.createContext<{
        open: boolean;
        setOpen: (open: boolean) => void;
    }>({ open: false, setOpen: () => undefined });

    function DropdownMenu({ children }: { children?: ReactNode }) {
        const [open, setOpen] = React.useState(false);
        return React.createElement(MenuContext.Provider, { value: { open, setOpen } }, children);
    }

    function DropdownMenuTrigger() {
        const { setOpen } = React.useContext(MenuContext);
        return React.createElement("button", {
            type: "button",
            "data-part": "menu-trigger",
            onClick: () => setOpen(true),
        });
    }

    function DropdownMenuContent({ children }: { children?: ReactNode }) {
        const { open } = React.useContext(MenuContext);
        return open ? React.createElement("div", { "data-part": "menu-content" }, children) : null;
    }

    function DropdownMenuItem({
        children,
        onSelect,
    }: {
        children?: ReactNode;
        onSelect?: (event: { preventDefault: () => void }) => void;
    }) {
        const { setOpen } = React.useContext(MenuContext);
        return React.createElement(
            "button",
            {
                type: "button",
                "data-part": "menu-item",
                onClick: () => {
                    let prevented = false;
                    onSelect?.({ preventDefault: () => { prevented = true; } });
                    if (!prevented) setOpen(false);
                },
            },
            children,
        );
    }

    return {
        DropdownMenu,
        DropdownMenuTrigger,
        DropdownMenuContent,
        DropdownMenuItem,
        DropdownMenuSeparator: () => React.createElement("hr"),
    };
});

vi.mock("@/app/components/ask-connex/AskConnexWatchDialog", async () => {
    const React = await import("react");
    return {
        default: ({ open }: { open: boolean }) =>
            (open ? React.createElement("div", { "data-part": "watch-dialog" }) : null),
    };
});

vi.mock("next-intl", () => ({
    useTranslations: () => (key: string) => key,
}));

vi.mock("@/app/components/ask-connex/AskConnexProvider", () => ({
    useAskConnex: () => ({ openWithPrompt: () => undefined }),
}));

vi.mock("@/app/hooks/useAskConnexSkills", () => ({
    useAskConnexSkills: () => [],
}));

/** The assistant permission the entry point reads, swapped per test. */
const assistantPermission = vi.hoisted(() => ({ current: "granted" as string }));

vi.mock("@/app/hooks/usePermissions", () => ({
    usePermissionCheck: () => assistantPermission.current,
}));

vi.mock("@/app/hooks/useActions", () => ({
    useActions: () => ({
        context: { record: { id: "42", type: "person", label: "Aiko Tanaka" } },
    }),
}));

/**
 * The mount point, read back out of the element registry.
 *
 * The helper hands back its container typed as a real `HTMLDivElement` because it stubs the global
 * document; the tree walk needs the interactive node, which is the one div the fake body owns.
 */
function containerOf(elements: InteractiveElement[]): InteractiveElement {
    const found = elements.find(
        (element) => element.tagName === "DIV" && element.parentNode?.tagName === "BODY",
    );
    if (found === undefined) throw new Error("Interactive container was not created");
    return found;
}

/** Walks the mounted tree, so a node that was created and then unmounted is not counted. */
function mounted(root: InteractiveElement, part: string): InteractiveElement | null {
    if (root.getAttribute("data-part") === part) return root;
    for (const child of root.childNodes) {
        if (child.nodeType !== 1) continue;
        const found = mounted(child, part);
        if (found !== null) return found;
    }
    return null;
}

function required(root: InteractiveElement, part: string): InteractiveElement {
    const element = mounted(root, part);
    if (element === null) throw new Error(`${part} was not rendered`);
    return element;
}

describe("the record entry point's watch dialog", () => {
    afterEach(() => {
        vi.unstubAllGlobals();
        assistantPermission.current = "granted";
    });

    /**
     * The dialog is the only way a watch is created, so it has to outlive the menu that offers it.
     * Rendering it inside the menu content made it unreachable: the selection closed the menu, the
     * portal unmounted, and the dialog went with it before it could ever paint.
     */
    it("survives the menu closing, because it is mounted beside the menu and not inside it",
        async () => {
            const interactive = installInteractiveDocument();
            const { createRoot } = await import("react-dom/client");
            const { default: AskConnexRecordEntry } = await import(
                "@/app/components/ask-connex/AskConnexRecordEntry");
            const root = createRoot(interactive.container);
            const container = containerOf(interactive.elements);

            await act(async () => {
                root.render(<AskConnexRecordEntry kind="person" />);
            });

            expect(mounted(container, "watch-dialog")).toBeNull();
            expect(mounted(container, "menu-content")).toBeNull();

            await act(async () => {
                interactive.dispatch("click", required(container, "menu-trigger"));
            });
            expect(mounted(container, "menu-content")).not.toBeNull();

            await act(async () => {
                interactive.dispatch("click", required(container, "menu-item"));
            });

            expect(
                mounted(container, "menu-content"),
                "Selecting the item still closes the menu",
            ).toBeNull();
            expect(
                mounted(container, "watch-dialog"),
                "The typed watch contract must still be mounted after the menu closes",
            ).not.toBeNull();

            await act(async () => root.unmount());
        });

    /**
     * The directory is already empty without `AI_USE`, so gating the watch on the record alone left
     * a member the server will certainly refuse looking at an assistant menu whose only entry was a
     * watch. The whole control is absent instead — and equally so while the lookup is unresolved,
     * because an answer that has not arrived is not a grant.
     */
    it.each(["denied", "unavailable"])(
        "renders no entry point at all when the assistant permission is %s",
        async (permission) => {
            assistantPermission.current = permission;
            const interactive = installInteractiveDocument();
            const { createRoot } = await import("react-dom/client");
            const { default: AskConnexRecordEntry } = await import(
                "@/app/components/ask-connex/AskConnexRecordEntry");
            const root = createRoot(interactive.container);
            const container = containerOf(interactive.elements);

            await act(async () => {
                root.render(<AskConnexRecordEntry kind="person" />);
            });

            expect(mounted(container, "menu-trigger")).toBeNull();
            expect(mounted(container, "watch-dialog")).toBeNull();

            await act(async () => root.unmount());
        });
});
