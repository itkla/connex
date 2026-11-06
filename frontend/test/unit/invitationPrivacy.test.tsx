/** @vitest-environment jsdom */
import { act, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import MembersPanel from "@/app/components/settings/MembersPanel";
import {
    createWorkspaceInvite, getBuiltInRoles, getWorkspaceInviteLinks, getWorkspaceInvites,
    getWorkspaceMembers, getWorkspaceOwnershipOffers, getWorkspaceRoles, removeWorkspaceMember, revokeWorkspaceInviteLink,
} from "@/app/lib/api";
import { toastError, toastSuccess } from "@/app/lib/toast";
import type { WorkspaceInvite } from "@/app/lib/types";
import english from "@/messages/en/workspace.json";
import japanese from "@/messages/ja/workspace.json";
import common from "@/messages/en/common.json";

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
    getWorkspaceRoles: vi.fn(), removeWorkspaceMember: vi.fn(), revokeWorkspaceInviteLink: vi.fn(),
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
    return {
        DropdownMenu: Pass, DropdownMenuTrigger: Pass, DropdownMenuContent: Pass,
        DropdownMenuItem: ({ children, onSelect, disabled }: { children?: ReactNode; onSelect?: () => void; disabled?: boolean }) =>
            <button disabled={disabled} onClick={onSelect}>{children}</button>,
    };
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
    clipboard.mockResolvedValue(undefined);
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
    vi.mocked(removeWorkspaceMember).mockResolvedValue(undefined);
});

afterEach(async () => {
    await act(async () => root.unmount());
    host.remove();
});

async function render(language: "en" | "ja") {
    await act(async () => root.render(
        <NextIntlClientProvider locale={language} messages={{ ...common, ...(language === "en" ? english : japanese) }} timeZone="UTC">
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
        const pendingHeading = [...host.querySelectorAll("h3")].find((heading) => heading.textContent === copy.pendingTitle);
        expect(pendingHeading?.parentElement?.querySelector("span")?.textContent).toBe("1");
        expect(getWorkspaceMembers).toHaveBeenCalledTimes(1);
        expect(getWorkspaceInvites).toHaveBeenCalledTimes(1);
        expect(clipboard).toHaveBeenCalledWith(expect.stringContaining("/invite#token=new-token"));
    });

    it.each(["en", "ja"] as const)("replaces a historical invite and keeps both counts correct in %s", async (language) => {
        const copy = (language === "en" ? english : japanese).WorkspaceMembers;
        vi.mocked(getWorkspaceInvites).mockResolvedValue([{
            id: "b".repeat(64), email: pending.email, role: "admin", status: "pending",
            createdAt: "2026-10-01T00:00:00Z",
        }]);
        await render(language);

        await submitInvitation();

        const rows = [...host.querySelectorAll("li")].filter((item) => item.textContent?.includes(pending.email));
        expect(rows).toHaveLength(1);
        expect(rows[0]?.textContent).toContain(copy.roleMember);
        expect(rows[0]?.textContent).toContain(copy.expires.replace("{date}", "2026-11-20"));
        expect(rows[0]?.textContent).not.toContain(copy.inviteNoExpiry);
        const pendingHeading = [...host.querySelectorAll("h3")].find((heading) => heading.textContent === copy.pendingTitle);
        expect(pendingHeading?.parentElement?.querySelector("span")?.textContent).toBe("1");
        expect(host.querySelector("section")?.textContent).toContain(copy.count.replace("{count}", "1"));
        expect(getWorkspaceMembers).toHaveBeenCalledTimes(1);
        expect(getWorkspaceInvites).toHaveBeenCalledTimes(1);
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


describe("member-flow outcomes", () => {
    it.each(["en", "ja"] as const)("offers manual recovery without a copied claim after clipboard refusal in %s", async (language) => {
        const copy = (language === "en" ? english : japanese).WorkspaceMembers;
        clipboard.mockRejectedValue(new Error("Clipboard refused"));
        await render(language);

        await submitInvitation();

        const dialog = document.querySelector('[role="dialog"]');
        const link = dialog?.querySelector("input");
        expect(dialog?.textContent).toContain(copy.copyFailedDescription);
        expect(link?.value).toContain("/invite#token=new-token");
        expect(link?.readOnly).toBe(true);
        expect(toastSuccess).toHaveBeenCalledWith(copy.invitationCreated);
        expect(toastSuccess).not.toHaveBeenCalledWith(copy.linkCopied);
        expect(toastError).toHaveBeenCalledWith(copy.copyFailed, { description: copy.copyFailedDescription });
        clipboard.mockResolvedValue(undefined);
        const retry = [...(dialog?.querySelectorAll("button") ?? [])].find((button) => button.textContent === copy.copyLink);
        await act(async () => retry?.click());
        expect(toastSuccess).toHaveBeenCalledWith(copy.linkCopied);
        expect(document.querySelector('[role="dialog"]')).toBeNull();
    });

    it.each(["ACCEPTED", "SKIPPED", "FAILED", "UNCONFIRMED"] as const)("reports %s without inferring recipient account state", async (emailDelivery) => {
        vi.mocked(createWorkspaceInvite).mockResolvedValue({ invite: pending, emailDelivery });
        await render("en");
        await submitInvitation();
        const copy = english.WorkspaceMembers;
        const key = emailDelivery === "ACCEPTED" ? "emailAccepted" : emailDelivery === "SKIPPED" ? "emailSkipped"
            : emailDelivery === "FAILED" ? "emailFailed" : "emailUnconfirmed";
        expect(host.querySelector('[role="status"]')?.textContent).toBe(copy[key]);
        expect(host.querySelector("section")?.textContent).not.toContain("registered");
    });

    it("replaces an invitation after reload to recover a copyable link", async () => {
        vi.mocked(getWorkspaceInvites).mockResolvedValue([{ ...pending, token: undefined }]);
        await render("en");
        const replace = [...host.querySelectorAll("button")].find((button) => button.textContent?.includes(english.WorkspaceMembers.replaceInvite));
        expect(replace).toBeDefined();
        await act(async () => replace?.click());
        expect(createWorkspaceInvite).toHaveBeenCalledWith(9, pending.email, "member");
        expect(clipboard).toHaveBeenCalledWith(expect.stringContaining("/invite#token=new-token"));
    });

    it("does not offer a replacement for an ungrantable historical owner invitation", async () => {
        vi.mocked(getWorkspaceInvites).mockResolvedValue([{ ...pending, role: "owner", token: undefined }]);
        await render("en");
        const replacement = [...host.querySelectorAll("button")].find((button) => button.textContent?.includes(english.WorkspaceMembers.replaceInvite));
        expect(replacement).toBeUndefined();
    });

    it.each(["en", "ja"] as const)("explains removal and reports detached ownership without revoking links in %s", async (language) => {
        const copy = (language === "en" ? english : japanese).WorkspaceMembers;
        vi.mocked(getWorkspaceMembers).mockResolvedValue([
            { id: 1, username: "owner", displayName: "Owner", email: "owner@example.test", role: "owner", builtInRole: "owner", status: "active" },
            { id: 2, username: "member", displayName: "Teammate", email: "member@example.test", role: "member", builtInRole: "member", status: "active" },
        ]);
        await render(language);
        const remove = [...host.querySelectorAll("button")].find((button) => button.textContent?.includes(copy.remove));
        expect(remove).toBeDefined();
        await act(async () => remove?.click());
        const dialog = document.querySelector('[role="dialog"]');
        expect(dialog?.textContent).toContain(copy.removeOwnershipConsequences);
        expect(dialog?.textContent).toContain(copy.removeRejoinConsequences);
        expect(dialog?.textContent).toContain(copy.reviewInvitations);
        expect(removeWorkspaceMember).not.toHaveBeenCalled();
        const confirm = [...(dialog?.querySelectorAll("button") ?? [])].find((button) => button.textContent === copy.remove);
        await act(async () => confirm?.click());
        expect(removeWorkspaceMember).toHaveBeenCalledWith(9, 2);
        expect(revokeWorkspaceInviteLink).not.toHaveBeenCalled();
        expect(toastSuccess).toHaveBeenCalledWith(copy.removed, { description: copy.removedDescription });
        expect(host.textContent).not.toContain("Teammate");
    });
});
