/** @vitest-environment jsdom */
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { act, type ReactNode } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import MembersPanel from "@/app/components/settings/MembersPanel";
import {
    createWorkspaceInvite, getBuiltInRoles, getWorkspaceInviteLinks, getWorkspaceInvites,
    getWorkspaceMembers, getWorkspaceOwnershipOffers, getWorkspaceRoles, removeWorkspaceMember, revokeWorkspaceInvite, revokeWorkspaceInviteLink,
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
    getWorkspaceRoles: vi.fn(), removeWorkspaceMember: vi.fn(), revokeWorkspaceInvite: vi.fn(), revokeWorkspaceInviteLink: vi.fn(),
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
vi.mock("@/components/ui/tabs", async () => {
    const { createContext, useContext } = await import("react");
    const Selection = createContext<{ value: string; onValueChange: (value: string) => void }>({ value: "email", onValueChange: noop });
    const Pass = ({ children }: { children?: ReactNode }) => <div>{children}</div>;
    return {
        Tabs: ({ children, value, onValueChange }: { children?: ReactNode; value: string; onValueChange: (value: string) => void }) =>
            <Selection.Provider value={{ value, onValueChange }}>{children}</Selection.Provider>,
        TabsList: Pass,
        TabsTrigger: ({ children, value }: { children?: ReactNode; value: string }) => {
            const selection = useContext(Selection);
            return <button role="tab" aria-selected={selection.value === value} onClick={() => selection.onValueChange(value)}>{children}</button>;
        },
        TabsContent: ({ children, value }: { children?: ReactNode; value: string }) =>
            useContext(Selection).value === value ? <div role="tabpanel">{children}</div> : null,
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
    vi.mocked(createWorkspaceInvite).mockReset().mockResolvedValue({ invite: pending });
    vi.mocked(removeWorkspaceMember).mockResolvedValue(undefined);
    vi.mocked(revokeWorkspaceInvite).mockResolvedValue(undefined);
    vi.mocked(revokeWorkspaceInviteLink).mockResolvedValue(undefined);
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

async function submitInvitation(email = pending.email) {
    const input = host.querySelector('input[type="email"]');
    const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set;
    const form = host.querySelector("form");
    if (!(input instanceof HTMLInputElement) || !setValue || !form) throw new Error("Missing invite form");
    await act(async () => {
        setValue.call(input, email);
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

        const recovery = host.querySelector('[role="region"]');
        const link = recovery?.querySelector("input");
        expect(recovery?.getAttribute("aria-label")).toBe(copy.copyFailed);
        expect(recovery?.textContent).toContain(copy.copyFailedDescription);
        expect(link?.value).toContain("/invite#token=new-token");
        expect(link?.readOnly).toBe(true);
        expect(document.querySelector('[role="dialog"]')).toBeNull();
        expect(toastSuccess).toHaveBeenCalledWith(copy.invitationCreated);
        expect(toastSuccess).not.toHaveBeenCalledWith(copy.linkCopied);
        expect(toastError).toHaveBeenCalledWith(copy.copyFailed, { description: copy.copyFailedDescription });
        clipboard.mockResolvedValue(undefined);
        const retry = [...(recovery?.querySelectorAll("button") ?? [])].find((button) => button.textContent === copy.copyLink);
        await act(async () => retry?.click());
        expect(toastSuccess).toHaveBeenCalledWith(copy.linkCopied);
        expect(host.querySelector('[role="region"]')).toBeNull();
    });

    it.each(["en", "ja"] as const)("announces mail failure while allowing another invitation after copy refusal in %s", async (language) => {
        const copy = (language === "en" ? english : japanese).WorkspaceMembers;
        const secondEmail = "another-invite@example.test";
        clipboard.mockRejectedValue(new Error("Clipboard refused"));
        vi.mocked(createWorkspaceInvite)
            .mockResolvedValueOnce({ invite: pending, emailDelivery: "FAILED" })
            .mockResolvedValueOnce({ invite: { ...pending, id: "b".repeat(64), email: secondEmail }, emailDelivery: "SKIPPED" });
        await render(language);
        const status = host.querySelector('[role="status"]');
        expect(status?.textContent).toBe("");
        expect(status?.getAttribute("aria-live")).toBe("polite");
        expect(status?.getAttribute("aria-atomic")).toBe("true");

        await submitInvitation();

        expect(host.querySelector('[role="status"]')).toBe(status);
        expect(status?.textContent).toBe(copy.emailFailed);
        expect(status?.closest('[aria-hidden="true"]')).toBeNull();
        expect(document.querySelector('[role="dialog"]')).toBeNull();
        expect(host.querySelector('[role="region"]')).not.toBeNull();

        await submitInvitation(secondEmail);

        expect(createWorkspaceInvite).toHaveBeenNthCalledWith(2, 9, secondEmail, "member");
        expect(status?.textContent).toBe(copy.emailSkipped);
        expect(host.querySelectorAll("li")).toHaveLength(3);
        expect(toastSuccess).not.toHaveBeenCalledWith(copy.linkCopied);
    });

    it.each(["en", "ja"] as const)("keeps link-tab copy recovery visible and clears it after revocation in %s", async (language) => {
        const copy = (language === "en" ? english : japanese).WorkspaceMembers;
        clipboard.mockRejectedValue(new Error("Clipboard refused"));
        vi.mocked(getWorkspaceInviteLinks).mockResolvedValue([{
            id: 7, token: "shareable-token", role: "member", usedCount: 0,
            maxUses: null, revoked: false, createdByLabel: "Workspace owner",
            expiresAt: "2026-11-20T00:00:00Z", createdAt: "2026-11-06T00:00:00Z",
        }]);
        await render(language);
        const tab = [...host.querySelectorAll("button")].find((button) => button.getAttribute("role") === "tab" && button.textContent === copy.inviteTabLink);
        await act(async () => tab?.click());
        expect(host.querySelector('input[type="email"]')).toBeNull();
        const copyButton = [...host.querySelectorAll("button")].find((button) => button.textContent === copy.copyLink);
        expect(copyButton).toBeDefined();

        await act(async () => copyButton?.click());

        expect(host.querySelector('[role="region"] input')?.getAttribute("value")).toContain("/invite-link#token=shareable-token");
        expect(document.querySelector('[role="dialog"]')).toBeNull();
        const revoke = [...host.querySelectorAll("button")].find((button) => button.textContent?.includes(copy.revoke));
        expect(revoke).toBeDefined();
        await act(async () => revoke?.click());
        expect(revokeWorkspaceInviteLink).toHaveBeenCalledWith(9, 7);
        expect(host.querySelector('[role="region"]')).toBeNull();
    });

    it.each(["en", "ja"] as const)("clears a failed email-invitation copy after revocation in %s", async (language) => {
        const copy = (language === "en" ? english : japanese).WorkspaceMembers;
        clipboard.mockRejectedValue(new Error("Clipboard refused"));
        await render(language);
        await submitInvitation();
        expect(host.querySelector('[role="region"]')).not.toBeNull();
        const revoke = [...host.querySelectorAll("button")].find((button) => button.textContent?.includes(copy.revoke));
        expect(revoke).toBeDefined();

        await act(async () => revoke?.click());

        expect(revokeWorkspaceInvite).toHaveBeenCalledWith(9, pending.id);
        expect(host.querySelector('[role="region"]')).toBeNull();
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


describe("member-removal ownership contract", () => {
    it("names every ownership type cleared by detachMemberContent in both confirmation and result", () => {
        const source = readFileSync(resolve(process.cwd(), "../backend/src/main/java/ooo/klae/connex/backend/services/UserOffboardingService.java"), "utf8");
        const start = source.indexOf("public void detachMemberContent(");
        const end = source.indexOf("/**", start);
        const method = source.slice(start, end);
        const labels: Record<string, { en: string; ja: string }> = {
            company: { en: "companies", ja: "会社" }, person: { en: "contacts", ja: "連絡先" },
            deal: { en: "deals", ja: "案件" }, campaign: { en: "campaigns", ja: "キャンペーン" },
            sequence: { en: "sequences", ja: "シーケンス" },
        };
        const cleared = [...method.matchAll(/(\w+)Mapper\.clearMember(?:Deal)?Ownership\(/g)].map((match) => match[1]);
        expect(cleared.length).toBeGreaterThan(0);
        for (const mapper of cleared) {
            const label = labels[mapper];
            if (!label) throw new Error(`Missing ownership copy for ${mapper}`);
            for (const key of ["removeOwnershipConsequences", "removedDescription"] as const) {
                expect(english.WorkspaceMembers[key]).toContain(label.en);
                expect(japanese.WorkspaceMembers[key]).toContain(label.ja);
            }
        }
        expect(method).toContain("taskMapper.unassignMemberTasks");
        for (const key of ["removeOwnershipConsequences", "removedDescription"] as const) {
            expect(english.WorkspaceMembers[key]).toContain("tasks");
            expect(japanese.WorkspaceMembers[key]).toContain("タスク");
        }
    });
});
