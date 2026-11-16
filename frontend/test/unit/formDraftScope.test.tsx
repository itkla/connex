/** @vitest-environment jsdom */
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useFormDraft, type FormDraftControls } from "@/app/hooks/useFormDraft";
import {
    advanceDraftKeyGeneration,
    clearAllDrafts,
    draftKey,
    readDraft,
    writeDraft,
    type DraftKeyParts,
} from "@/app/lib/formDrafts";

type Draft = { content: string };

const ORIGIN: DraftKeyParts = { userId: 7, workspaceId: 9, formType: "noteEditor", scope: "new" };
const SAVED = { ...ORIGIN, scope: "note:41" };
const captured = new Map<string, FormDraftControls<Draft>>();
let root: Root;

function Capture({ name = "editor", parts = ORIGIN }: { name?: string; parts?: DraftKeyParts }) {
    captured.set(name, useFormDraft<Draft>({ keyParts: parts, version: 1 }));
    return null;
}

function controls(name = "editor"): FormDraftControls<Draft> {
    const draft = captured.get(name);
    if (!draft) throw new Error("The draft hook did not render");
    return draft;
}

function seed(parts: DraftKeyParts, content: string): void {
    const key = draftKey(parts);
    advanceDraftKeyGeneration(key);
    writeDraft(key, { version: 1, formType: parts.formType, scope: parts.scope, data: { content } });
}

function flush(): void {
    window.dispatchEvent(new Event("pagehide"));
}

beforeEach(() => {
    vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true);
    clearAllDrafts();
    captured.clear();
    const container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
});

afterEach(async () => {
    await act(async () => root.unmount());
    clearAllDrafts();
    document.body.innerHTML = "";
    vi.unstubAllGlobals();
});

describe("a draft scope moving from a new entity to its saved identity", () => {
    it("rekeys immediately while keeping the original user and workspace after a caller rerender", async () => {
        await act(async () => root.render(<Capture />));
        const { persist, rekeyScope } = controls();
        persist({ content: "Created note" });
        flush();

        const otherOwner = { ...ORIGIN, userId: 8, workspaceId: 5 };
        await act(async () => root.render(<Capture parts={otherOwner} />));
        rekeyScope(SAVED.scope);
        persist({ content: "Later refused edits" });
        flush();

        expect(readDraft(ORIGIN, { version: 1 })).toBeNull();
        expect(readDraft(SAVED, { version: 1 })).toMatchObject({
            scope: SAVED.scope,
            data: { content: "Later refused edits" },
        });
        expect(readDraft({ ...otherOwner, scope: SAVED.scope }, { version: 1 })).toBeNull();
    });

    it("cancels the previous pending write without clearing another owner's source or destination", async () => {
        await act(async () => root.render(<Capture />));
        controls().persist({ content: "Old pending draft" });
        seed(ORIGIN, "Another new-note editor");
        seed(SAVED, "Another saved-note editor");

        controls().rekeyScope(SAVED.scope);
        controls().clear();
        flush();

        expect(readDraft(ORIGIN, { version: 1 })?.data).toEqual({ content: "Another new-note editor" });
        expect(readDraft(SAVED, { version: 1 })?.data).toEqual({ content: "Another saved-note editor" });
    });

    it("keeps generation ownership when another editor writes the saved note's draft", async () => {
        await act(async () => root.render(<><Capture /><Capture name="other" parts={SAVED} /></>));
        controls().rekeyScope(SAVED.scope);
        controls().persist({ content: "Older pending edits" });
        controls("other").persist({ content: "Newer pending edits" });
        flush();
        controls().clear();

        expect(readDraft(SAVED, { version: 1 })?.data).toEqual({ content: "Newer pending edits" });
    });

    it("does not resurrect a pending rekeyed draft after the store is cleared", async () => {
        await act(async () => root.render(<Capture />));
        controls().rekeyScope(SAVED.scope);
        controls().persist({ content: "Pending edits before logout" });
        clearAllDrafts();
        flush();

        expect(readDraft(SAVED, { version: 1 })).toBeNull();
        expect(readDraft(ORIGIN, { version: 1 })).toBeNull();
    });

    it("retains a pending draft when the scope has not changed", async () => {
        await act(async () => root.render(<Capture />));
        controls().persist({ content: "Unsaved new note" });
        controls().rekeyScope(ORIGIN.scope);
        flush();

        expect(readDraft(ORIGIN, { version: 1 })?.data).toEqual({ content: "Unsaved new note" });
    });
});
