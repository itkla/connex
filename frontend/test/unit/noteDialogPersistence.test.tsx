/** @vitest-environment jsdom */

import { cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NoteDialogForm } from "@/app/components/activity/notes/NoteDialog";

const api = vi.hoisted(() => ({ update: vi.fn(), create: vi.fn(), refresh: vi.fn(), report: vi.fn() }));
vi.mock("@/app/lib/api", () => ({ updateNote: api.update, createNote: api.create }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: api.refresh }) }));
vi.mock("next-intl", () => ({ useTranslations: () => (key: string) => key }));
vi.mock("@/app/hooks/useApiErrorToast", () => ({ useApiErrorToast: () => api.report }));
vi.mock("@/app/lib/toast", () => ({ toastSuccess: vi.fn() }));
vi.mock("@/components/ui/dialog-status-cover", async (importOriginal) => {
    const actual = await importOriginal<typeof import("@/components/ui/dialog-status-cover")>();
    return { ...actual, DialogStatusCover: () => null };
});
vi.mock("next/dynamic", () => ({
    default: () => function MockRichNoteEditor({ value }: { value: string }) {
        return <textarea value={value} readOnly />;
    },
}));

afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("note dialog persistence", () => {
    it.each([
        "  Plain text  with spacing\n\nA second paragraph.\n",
        "  Existing [Kenji Tanaka](person:42)\n\n- Existing item\n",
    ])("keeps existing body bytes when saving metadata: %s", async (content) => {
        api.update.mockResolvedValue({ id: 71 });
        const { getByRole } = render(<NoteDialogForm
            note={{ id: 71, content, title: "Original", author: 1, person: 42, deal: 73,
                createdAt: "2026-11-04T00:00:00Z", updatedAt: "2026-11-04T00:00:00Z" }}
            persons={[]} deals={[]} currentUserId={1} defaultPerson={null} defaultDeal={null} defaultContent=""
            onSubmittingChange={() => {}} onCancel={() => {}} onClose={() => {}}
        />);
        fireEvent.change(getByRole("textbox", { name: "titleLabel" }), { target: { value: "Revised" } });
        fireEvent.click(getByRole("button", { name: "save" }));

        await waitFor(() => expect(api.update).toHaveBeenCalledWith(71, expect.objectContaining({
            content, title: "Revised", person: 42, deal: 73,
        }), undefined));
        expect(api.report).not.toHaveBeenCalled();
    });
});
