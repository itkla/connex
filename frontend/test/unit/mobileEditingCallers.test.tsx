/** @vitest-environment jsdom */
import type { ReactNode } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import EditableCell from "@/app/components/records/EditableCell";
import { CustomFieldValueCell } from "@/app/components/records/CustomFieldValueCell";
import EditTaskSheet from "@/app/components/activity/tasks/EditTaskSheet";
import type { Task } from "@/app/lib/types";

vi.mock("next-intl", () => ({
    useTranslations: () => (key: string) => key,
    useLocale: () => "en",
    useTimeZone: () => "UTC",
}));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));
vi.mock("@/app/hooks/useApiErrorToast", () => ({ useApiErrorToast: () => vi.fn() }));
vi.mock("@/app/hooks/useRecordTargetSearch", () => ({
    useDealTargetSearch: () => ({ deals: [], onInputValueChange: vi.fn(), serverSearch: {} }),
    useMemberTargetSearch: () => ({ users: [], onInputValueChange: vi.fn(), serverSearch: {} }),
}));
vi.mock("@/app/components/records/RecordSelect", () => ({ default: () => null }));
vi.mock("@/app/components/ConfirmDiscardDialog", () => ({ default: () => null }));
vi.mock("@/components/ui/drawer", () => {
    const Part = ({ children }: { children?: ReactNode }) => <div>{children}</div>;
    return {
        Drawer: ({ open, children }: { open: boolean; children?: ReactNode }) => open ? <>{children}</> : null,
        DrawerContent: Part, DrawerHeader: Part, DrawerTitle: Part, DrawerDescription: Part,
        DrawerFooter: Part, DrawerClose: Part,
    };
});
afterEach(cleanup);

function expectResponsiveText(control: Element) {
    expect(control.classList.contains("text-base")).toBe(true);
    expect(control.classList.contains("md:text-sm")).toBe(true);
    expect(control.classList.contains("text-sm")).toBe(false);
    expect(control.classList.contains("text-xs")).toBe(false);
}

describe("mobile fonts after caller class merging", () => {
    it("keeps inline record editing at 16px below md", () => {
        render(<EditableCell value="Existing" ariaLabel="Edit name" onCommit={async () => {}} />);
        fireEvent.doubleClick(screen.getByRole("button", { name: "Edit name" }));
        expectResponsiveText(screen.getByRole("textbox"));
    });
    it.each(["text", "textarea"] as const)("keeps custom %s editing at 16px below md", (fieldType) => {
        render(<CustomFieldValueCell entityType="person" entityId={1}
            field={{ definitionId: 7, fieldType, options: null, required: false, label: "Extra" }}
            value="Existing" onChange={() => {}} />);
        fireEvent.doubleClick(screen.getByRole("button", { name: "Existing" }));
        expectResponsiveText(screen.getByRole("textbox"));
    });
    it("preserves the autofocused task editor's mobile size with the sheet's actual classes", () => {
        const task: Task = {
            id: 7, description: "Follow up", completed: false, status: "todo", position: 0,
            assignedToId: 1, createdAt: "", updatedAt: "",
        };
        const { container } = render(<EditTaskSheet task={task} open onOpenChange={() => {}} deals={[]} />);
        const editor = container.querySelector('[contenteditable="true"]');
        if (!editor) throw new Error("Missing task editor");
        expectResponsiveText(editor);
        expect(document.activeElement).toBe(editor);
        const dueDate = container.querySelector('input[type="date"]');
        if (!dueDate) throw new Error("Missing due date input");
        expectResponsiveText(dueDate);
    });
});
