/** @vitest-environment jsdom */

import type { ReactNode } from "react";
import { act, cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import NoteEditorView from "@/app/components/activity/notes/NoteEditorView";
import type { Contact, Deal, Note } from "@/app/lib/types";
import { workspacePinnedRequest } from "@/app/lib/renderedWorkspace";

const api = vi.hoisted(() => ({
    update: vi.fn(), create: vi.fn(), report: vi.fn(), translate: (key: string) => key,
}));
vi.mock("@/app/lib/api", () => ({
    updateNote: api.update, createNote: api.create, isWorkspaceUnavailableError: () => false,
}));
vi.mock("next-intl", () => ({ useTranslations: () => api.translate }));
vi.mock("@/app/hooks/useWorkspace", () => ({ useWorkspace: () => ({ activeWorkspaceId: 7 }) }));
vi.mock("@/app/hooks/useApiErrorToast", () => ({ useApiErrorToast: () => api.report }));
vi.mock("@/app/hooks/useNavTrail", () => ({ CrumbLabel: () => null }));
vi.mock("@/app/components/PageShell", () => ({ PageShell: ({ children }: { children: ReactNode }) => children }));
vi.mock("@/app/components/activity/notes/BacklinksPanel", () => ({ default: () => null }));
vi.mock("next/dynamic", () => ({
    default: () => function MockRichNoteEditor({ value, onChange }: { value: string; onChange: (value: string) => void }) {
        return <textarea aria-label="Note body" value={value} onChange={(event) => onChange(event.target.value)} />;
    },
}));

const timestamp = "2026-11-04T00:00:00Z";
const contact: Contact = {
    id: 42, name: "Linked contact", email: "", phone: "", title: "", imageUrl: "",
    createdAt: timestamp, updatedAt: timestamp,
};
const deal: Deal = {
    id: 73, name: "Linked deal", value: 0, actualValue: 0, currency: "JPY", pipeline: null,
    stage: null, company: null, position: 0, createdAt: timestamp, updatedAt: timestamp,
};
const unlinked: Note = {
    id: 71, content: "Original", title: "Original title", author: 1, visibility: "workspace",
    createdAt: timestamp, updatedAt: timestamp,
};
const linked: Note = { ...unlinked, person: contact.id, deal: deal.id };

function editor(note: Note) {
    return <NoteEditorView note={note} currentUserId={1} persons={[contact]} deals={[deal]}
        users={[]} returnPath="/activity/notes" />;
}

function flush() {
    fireEvent(window, new Event("beforeunload"));
}

beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    api.update.mockResolvedValue(unlinked);
});
afterEach(cleanup);

describe("full-page note links", () => {
    const company = { type: "company", id: 9, label: "Linked company" } as const;
    const linkedWithCompany: Note = { ...linked, references: [company], updatedAt: "2026-11-04 09:00:00" };

    it.each([
        ["older", "2026-11-04 09:59:59"],
        ["equal ISO", "2026-11-04T10:00:00Z"],
        ["equal offset", "2026-11-04T12:00:00+02:00"],
    ])("keeps acknowledged removals when %s props arrive after the save", async (_label, updatedAt) => {
        const saved: Note = { ...unlinked, references: [], updatedAt: "2026-11-04 10:00:00" };
        api.update.mockResolvedValue(saved);
        const view = render(editor(linkedWithCompany));
        fireEvent.change(view.getByRole("textbox", { name: "Note body" }), { target: { value: "Removed all links" } });
        flush();
        await waitFor(() => expect(view.queryByRole("link", { name: company.label })).toBeNull());
        view.rerender(editor({ ...linkedWithCompany, updatedAt }));

        for (const name of [contact.name, deal.name, company.label]) {
            expect(view.queryByRole("link", { name })).toBeNull();
        }
        fireEvent.change(view.getByRole("textbox", { name: "Note body" }), { target: { value: "Next edit" } });
        flush();
        await waitFor(() => expect(api.update).toHaveBeenLastCalledWith(71, expect.objectContaining({
            content: "Next edit", person: null, deal: null,
        }), workspacePinnedRequest(7)));
    });

    it("adopts strictly newer props after an acknowledged save without replacing unsaved text", async () => {
        api.update.mockResolvedValue({ ...unlinked, references: [], updatedAt: "2026-11-04 10:00:00" });
        const view = render(editor(linkedWithCompany));
        fireEvent.change(view.getByRole("textbox", { name: "Note body" }), { target: { value: "Removed all links" } });
        flush();
        await waitFor(() => expect(view.queryByRole("link", { name: company.label })).toBeNull());
        fireEvent.change(view.getByRole("textbox", { name: "Note body" }), { target: { value: "Unsaved text" } });
        view.rerender(editor({ ...linkedWithCompany, updatedAt: "2026-11-04T10:00:01Z" }));

        for (const name of [contact.name, deal.name, company.label]) {
            expect(view.getByRole("link", { name })).toBeTruthy();
        }
        expect(view.getByRole("textbox", { name: "Note body" })).toHaveProperty("value", "Unsaved text");
    });

    it.each([
        ["missing prop", "2026-11-04 10:00:00", undefined],
        ["invalid prop", "2026-11-04 10:00:00", "invalid"],
        ["missing save", undefined, "2026-11-04 09:00:00"],
        ["invalid save", "invalid", "2026-11-04 09:00:00"],
    ])("adopts props with a %s timestamp using the existing fallback", async (_label, savedAt, propAt) => {
        const saved: Note = { ...unlinked, references: [], updatedAt: savedAt ?? timestamp };
        const refreshed: Note = { ...linkedWithCompany, updatedAt: propAt ?? timestamp };
        if (savedAt === undefined) Reflect.deleteProperty(saved, "updatedAt");
        if (propAt === undefined) Reflect.deleteProperty(refreshed, "updatedAt");
        api.update.mockResolvedValue(saved);
        const view = render(editor(linkedWithCompany));
        fireEvent.change(view.getByRole("textbox", { name: "Note body" }), { target: { value: "Removed all links" } });
        flush();
        await waitFor(() => expect(view.queryByRole("link", { name: company.label })).toBeNull());
        view.rerender(editor(refreshed));

        for (const name of [contact.name, deal.name, company.label]) {
            expect(view.getByRole("link", { name })).toBeTruthy();
        }
    });

    it("sends held links and reconciles an absent-link save response into the chip and next save", async () => {
        const view = render(editor(linked));
        expect(view.getByRole("link", { name: contact.name })).toBeTruthy();
        expect(view.getByRole("link", { name: deal.name }).getAttribute("href")).toBe(`/records/deals/${deal.id}`);
        fireEvent.change(view.getByRole("textbox", { name: "Note body" }), { target: { value: "Edited" } });
        flush();

        await waitFor(() => expect(api.update).toHaveBeenCalledWith(71, expect.objectContaining({
            content: "Edited", person: 42, deal: 73,
        }), workspacePinnedRequest(7)));
        await waitFor(() => expect(view.queryByRole("link", { name: contact.name })).toBeNull());
        expect(view.queryByRole("link", { name: deal.name })).toBeNull();
        fireEvent.change(view.getByRole("textbox", { name: "Note body" }), { target: { value: "Edited again" } });
        flush();

        await waitFor(() => expect(api.update).toHaveBeenLastCalledWith(71, expect.objectContaining({
            content: "Edited again", person: null, deal: null,
        }), workspacePinnedRequest(7)));
    });

    it("shows every resolved record reference once and adopts saved company references", async () => {
        const company = { type: "company", id: 9, label: "Linked company" } as const;
        const note: Note = { ...linked, references: [
            { type: "person", id: contact.id, label: contact.name },
            { type: "deal", id: deal.id, label: deal.name },
            company,
        ] };
        api.update.mockResolvedValue({ ...linked, references: [{ ...company, id: 10, label: "Updated company" }] });
        const view = render(editor(note));
        expect(view.getAllByRole("link", { name: contact.name })).toHaveLength(1);
        expect(view.getAllByRole("link", { name: deal.name })).toHaveLength(1);
        expect(view.getByRole("link", { name: company.label }).getAttribute("href")).toBe("/records/companies/9");
        fireEvent.change(view.getByRole("textbox", { name: "Note body" }), { target: { value: "Edited company reference" } });
        flush();
        await waitFor(() => expect(view.getByRole("link", { name: "Updated company" }).getAttribute("href")).toBe("/records/companies/10"));
        expect(view.queryByRole("link", { name: company.label })).toBeNull();
        view.rerender(editor({ ...unlinked, updatedAt: "2026-11-04T00:00:01Z" }));
        expect(view.queryByRole("link", { name: "Updated company" })).toBeNull();
    });

    it.each([
        ["absent", unlinked],
        ["null", { ...unlinked, person: null, deal: null }],
    ] as const)("reconciles %s refreshed links without replacing unsaved text", async (_label, refreshed) => {
        const view = render(editor(linked));
        const content = "  Local [Contact](person:42)\n\nUnsaved text.\n";
        fireEvent.change(view.getByRole("textbox", { name: "Note body" }), { target: { value: content } });
        view.rerender(editor(refreshed));

        expect(view.queryByRole("link", { name: contact.name })).toBeNull();
        expect(view.getByRole("textbox", { name: "Note body" })).toHaveProperty("value", content);
        flush();
        await waitFor(() => expect(api.update).toHaveBeenCalledWith(71, expect.objectContaining({
            content, person: null, deal: null,
        }), workspacePinnedRequest(7)));
    });

    it("reconciles each refreshed link independently", async () => {
        api.update.mockResolvedValue({ ...unlinked, deal: deal.id });
        const view = render(editor(linked));
        view.rerender(editor({ ...unlinked, deal: deal.id }));

        expect(view.queryByRole("link", { name: contact.name })).toBeNull();
        expect(view.getByRole("link", { name: deal.name })).toBeTruthy();
        fireEvent.change(view.getByRole("textbox", { name: "titlePlaceholder" }), { target: { value: "Edited title" } });
        flush();
        await waitFor(() => expect(api.update).toHaveBeenCalledWith(71, expect.objectContaining({
            content: "Original", title: "Edited title", person: null, deal: 73,
        }), workspacePinnedRequest(7)));
    });

    it("keeps refreshed links when an older save response arrives afterward", async () => {
        let resolveSave: ((saved: Note) => void) | undefined;
        api.update.mockImplementationOnce(() => new Promise<Note>((resolve) => { resolveSave = resolve; }));
        const view = render(editor(linked));
        fireEvent.change(view.getByRole("textbox", { name: "Note body" }), { target: { value: "First edit" } });
        flush();
        view.rerender(editor(unlinked));
        await act(async () => {
            if (!resolveSave) throw new Error("Save was not started");
            resolveSave({ ...linked, content: "First edit" });
        });

        expect(view.queryByRole("link", { name: contact.name })).toBeNull();
        fireEvent.change(view.getByRole("textbox", { name: "Note body" }), { target: { value: "Second edit" } });
        flush();
        await waitFor(() => expect(api.update).toHaveBeenLastCalledWith(71, expect.objectContaining({
            content: "Second edit", person: null, deal: null,
        }), workspacePinnedRequest(7)));
    });
});
