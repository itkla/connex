/** @vitest-environment jsdom */
import { act, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import MembersPanel from "@/app/components/settings/MembersPanel";
import {
    createWorkspaceInvite, getBuiltInRoles, getWorkspaceInviteLinks, getWorkspaceInvites,
    getWorkspaceMembers, getWorkspaceOwnershipOffers, getWorkspaceRoles,
} from "@/app/lib/api";
import { toastSuccess } from "@/app/lib/toast";
import type { WorkspaceInvite } from "@/app/lib/types";
import english from "@/messages/en/workspace.json";
import japanese from "@/messages/ja/workspace.json";

const noop = vi.hoisted(() => () => undefined);
const permissions = vi.hoisted(() => new Set(["MEMBER_MANAGE", "ROLE_MANAGE"]));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: noop, push: noop, replace: noop }) }));
vi.mock("@/app/hooks/useWorkspace", () => ({ useWorkspace: () => ({
    activeWorkspaceId: 9, activeWorkspace: { id: 9, name: "Invite team", role: "owner" },
}) }));
vi.mock("@/app/hooks/usePermissions", () => ({ usePermission: () => true, useGrantedPermissions: () => permissions }));
vi.mock("@/app/hooks/useApiErrorToast", () => ({ useApiErrorToast: () => noop }));
vi.mock("@/app/hooks/usePasskeyStepUpError", () => ({ usePasskeyStepUpErrorHandler: () => () => false }));
vi.mock("@/app/lib/toast", () => ({ toastSuccess: vi.fn(), toastError: vi.fn() }));
vi.mock("@/app/lib/api", async (importOriginal) => ({
    ...await importOriginal<typeof import("@/app/lib/api")>(),
    createWorkspaceInvite: vi.fn(), getBuiltInRoles: vi.fn(), getWorkspaceInviteLinks: vi.fn(),
    getWorkspaceInvites: vi.fn(), getWorkspaceMembers: vi.fn(), getWorkspaceOwnershipOffers: vi.fn(),
    getWorkspaceRoles: vi.fn(),
}));
vi.mock("@/app/components/motion/Rise", () => ({ default: ({ children }: { children?: ReactNode }) => <>{children}</> }));
vi.mock("@/app/components/records/DeleteRecordDialog", () => ({ default: () => null }));
vi.mock("@/app/components/settings/PasskeyApprovalCodeDialog", () => ({ default: () => null }));
vi.mock("@/app/components/settings/ConfinementAcknowledgementDialog", () => ({ default: () => null }));
vi.mock("@/components/ui/select", () => {
    const Pass = ({ children }: { children?: ReactNode }) => <div>{children}</div>;
    return {
        Select: Pass, SelectTrigger: Pass, SelectValue: () => null, SelectContent: () => null,
        SelectGroup: Pass, SelectItem: Pass, SelectLabel: Pass,
    };
});
vi.mock("@/components/ui/tabs", () => {
    const Pass = ({ children }: { children?: ReactNode }) => <div>{children}</div>;
    return {
        Tabs: Pass, TabsList: Pass, TabsTrigger: Pass,
        TabsContent: ({ children, value }: { children?: ReactNode; value: string }) => value === "email" ? <div>{children}</div> : null,
    };
});
vi.mock("@/components/ui/dropdown-menu", () => {
    const Pass = ({ children }: { children?: ReactNode }) => <div>{children}</div>;
    return { DropdownMenu: Pass, DropdownMenuTrigger: Pass, DropdownMenuContent: () => null, DropdownMenuItem: Pass };
});

let root: Root;
let host: HTMLDivElement;
const clipboard = vi.fn().mockResolvedValue(undefined);
const pending: WorkspaceInvite = {
    id: "a".repeat(64), email: "invited@example.test", role: "member", status: "pending",
    token: "new-token", expiresAt: "2026-11-20T00:00:00Z", createdAt: "2026-11-06T00:00:00Z",
};

beforeEach(() => {
    Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
    vi.clearAllMocks();
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText: clipboard } });
    host = document.createElement("div");
    document.body.append(host);
    root = createRoot(host);
    vi.mocked(getWorkspaceMembers).mockResolvedValue([{
        id: 1, username: "owner", displayName: "Workspace owner", email: "owner@example.test",
        role: "owner", builtInRole: "owner", roleId: null, status: "active",
    }]);
    vi.mocked(getBuiltInRoles).mockResolvedValue([{ id: 0, name: "member", permissions: [] }]);
    vi.mocked(getWorkspaceInvites).mockResolvedValue([]);
    vi.mocked(getWorkspaceInviteLinks).mockResolvedValue([]);
    vi.mocked(getWorkspaceOwnershipOffers).mockResolvedValue([]);
    vi.mocked(getWorkspaceRoles).mockResolvedValue([]);
    vi.mocked(createWorkspaceInvite).mockResolvedValue({ invite: pending });
});

afterEach(async () => {
    await act(async () => root.unmount());
    host.remove();
});

async function render(language: "en" | "ja") {
    await act(async () => root.render(
        <NextIntlClientProvider locale={language} messages={language === "en" ? english : japanese} timeZone="UTC">
            <MembersPanel currentUserId={1} presentation="consolidated" />
        </NextIntlClientProvider>,
    ));
}

async function submitInvitation() {
    const input = host.querySelector('input[type="email"]');
    const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set;
    const form = host.querySelector("form");
    if (!(input instanceof HTMLInputElement) || !setValue || !form) throw new Error("Missing invite form");
    await act(async () => {
        setValue.call(input, pending.email);
        input.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await act(async () => { form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })); });
}

describe("invitation disclosure in the member-management panel", () => {
    it.each(["en", "ja"] as const)("adds an address-only invite without changing the member count in %s", async (language) => {
        const copy = (language === "en" ? english : japanese).WorkspaceMembers;
        await render(language);
        const memberSection = host.querySelector("section");
        expect(memberSection?.textContent).toContain(copy.count.replace("{count}", "1"));

        await submitInvitation();

        expect(createWorkspaceInvite).toHaveBeenCalledWith(9, pending.email, "member");
        expect(toastSuccess).toHaveBeenCalledWith(copy.invitationCreated);
        expect(memberSection?.textContent).toContain(copy.count.replace("{count}", "1"));
        expect(memberSection?.textContent).not.toContain(pending.email);
        const row = [...host.querySelectorAll("li")].find((item) => item.textContent?.includes(pending.email));
        expect(row?.textContent).toContain(pending.email);
        expect(row?.textContent).toContain(copy.expires.replace("{date}", "2026-11-20"));
        expect(clipboard).toHaveBeenCalledWith(expect.stringContaining("/invite#token=new-token"));
    });

    it.each(["en", "ja"] as const)("renders a historical invitation with omitted expiry in %s", async (language) => {
        const copy = (language === "en" ? english : japanese).WorkspaceMembers;
        vi.mocked(getWorkspaceInvites).mockResolvedValue([{
            id: "b".repeat(64), email: "historical@example.test", role: "member", status: "pending",
            createdAt: "2026-10-01T00:00:00Z",
        }]);

        await render(language);

        const row = [...host.querySelectorAll("li")].find((item) => item.textContent?.includes("historical@example.test"));
        expect(row?.textContent).toContain(copy.inviteNoExpiry);
        expect(row?.textContent).not.toContain("undefined");
    });
});
