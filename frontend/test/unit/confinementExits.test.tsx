// @vitest-environment jsdom
import { act, useEffect } from "react";
import { createRoot, type Root } from "react-dom/client";
import { NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError, type PrivilegedMfaSettleOutcome } from "@/app/lib/api";
import type { ConfinementExits, ConfinementWorkspaceExit } from "@/app/hooks/useConfinementExits";
import enAccount from "@/messages/en/account.json";

const api = vi.hoisted(() => ({
    leaveOrganization: vi.fn<(orgId: number) => Promise<PrivilegedMfaSettleOutcome | null>>(),
    settle: vi.fn<(settlement: string) => Promise<PrivilegedMfaSettleOutcome>>(),
    refresh: vi.fn(),
    toastError: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: api.refresh }) }));
vi.mock("@/app/lib/toast", () => ({ toastError: api.toastError }));

vi.mock("@/app/lib/api", async (importOriginal) => ({
    ...await importOriginal<typeof import("@/app/lib/api")>(),
    leaveOrganization: api.leaveOrganization,
    settlePrivilegedMfaStanding: api.settle,
}));

let container: HTMLDivElement;
let root: Root;
let latest: ConfinementExits | null = null;

async function mountExits(workspaceExit?: ConfinementWorkspaceExit) {
    const { useConfinementExits } = await import("@/app/hooks/useConfinementExits");
    function Probe() {
        const exits = useConfinementExits(workspaceExit);
        useEffect(() => {
            latest = exits;
        });
        return null;
    }
    await act(async () => root.render(
        <NextIntlClientProvider locale="en" messages={enAccount}>
            <Probe />
        </NextIntlClientProvider>,
    ));
}

function workspaceExitFor(
    leave: ConfinementWorkspaceExit["leave"],
    reconcile: ConfinementWorkspaceExit["reconcile"] = vi.fn(async () => "applied" as const),
): ConfinementWorkspaceExit {
    return { leave, reconcile };
}

function current(): ConfinementExits {
    if (latest === null) throw new Error("useConfinementExits did not render");
    return latest;
}

beforeEach(() => {
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
    latest = null;
    api.leaveOrganization.mockReset();
    api.settle.mockReset();
    api.settle.mockResolvedValue("applied");
    api.refresh.mockReset();
    api.toastError.mockReset();
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
});

describe("leaving what confines a privileged account", () => {
    it("leaves an organization and forgets an earlier refusal of it", async () => {
        api.leaveOrganization
            .mockRejectedValueOnce(new ApiError("Last owner", 400, "ORG_LAST_OWNER"))
            .mockResolvedValueOnce("applied");
        await mountExits();
        const target = { kind: "organization" as const, orgId: 3, name: "Acme" };

        let left = true;
        await act(async () => {
            left = await current().leave(target);
        });
        expect(left).toBe(false);
        expect(current().refusals["organization:3"]).toBe("ORG_LAST_OWNER");
        expect(api.refresh).not.toHaveBeenCalled();

        await act(async () => {
            left = await current().leave(target);
        });
        expect(left).toBe(true);
        expect(api.leaveOrganization).toHaveBeenLastCalledWith(3);
        expect(api.refresh).toHaveBeenCalledOnce();
        expect(current().refusals["organization:3"]).toBeUndefined();
        expect(current().leavingKey).toBeNull();
    });

    it.each([
        "WORKSPACE_LAST_OWNER",
        "WORKSPACE_RECOVERY_OWNER_REQUIRED",
        "SOLE_NAMED_DOCUMENT_APPROVER",
    ])("keeps the server's %s refusal of a workspace exit", async (code) => {
        const leaveWorkspace = vi.fn(async (): Promise<PrivilegedMfaSettleOutcome | null> => {
            throw new ApiError("Refused", code === "SOLE_NAMED_DOCUMENT_APPROVER" ? 409 : 400, code);
        });
        const workspaceExit = workspaceExitFor(leaveWorkspace);
        await mountExits(workspaceExit);

        await act(async () => {
            await current().leave({ kind: "workspace", workspaceId: 7, name: "Sales" });
        });

        expect(leaveWorkspace).toHaveBeenCalledWith(7);
        expect(workspaceExit.reconcile).not.toHaveBeenCalled();
        expect(current().refusals["workspace:7"]).toBe(code);
    });

    it("counts an organization the account already left elsewhere as left", async () => {
        api.leaveOrganization.mockRejectedValueOnce(new ApiError("Not a member", 404, "ORG_NOT_MEMBER"));
        await mountExits();

        let left = false;
        await act(async () => {
            left = await current().leave({ kind: "organization", orgId: 3, name: "Acme" });
        });

        expect(left).toBe(true);
        expect(api.settle).toHaveBeenCalledWith("exit");
        expect(api.refresh).toHaveBeenCalledOnce();
        expect(current().refusals["organization:3"]).toBeUndefined();
    });

    it("still counts an organization exit when the standing cannot be read again, and says so", async () => {
        api.leaveOrganization.mockResolvedValueOnce("failed");
        await mountExits();

        let left = false;
        await act(async () => {
            left = await current().leave({ kind: "organization", orgId: 3, name: "Acme" });
        });

        expect(left).toBe(true);
        expect(api.refresh).toHaveBeenCalledOnce();
        expect(api.toastError).toHaveBeenCalledWith(enAccount.AccountSecurity.exitRefreshFailed);
        expect(current().refusals["organization:3"]).toBeUndefined();
    });

    it("says so when an organization already left elsewhere cannot be read again", async () => {
        api.leaveOrganization.mockRejectedValueOnce(new ApiError("Not a member", 404, "ORG_NOT_MEMBER"));
        api.settle.mockResolvedValueOnce("failed");
        await mountExits();

        let left = false;
        await act(async () => {
            left = await current().leave({ kind: "organization", orgId: 3, name: "Acme" });
        });

        expect(left).toBe(true);
        expect(api.toastError).toHaveBeenCalledWith(enAccount.AccountSecurity.exitRefreshFailed);
    });

    it("leaves a workspace through the surface without a note when the standing settles", async () => {
        const workspaceExit = workspaceExitFor(vi.fn(async () => "applied" as const));
        await mountExits(workspaceExit);

        let left = false;
        await act(async () => {
            left = await current().leave({ kind: "workspace", workspaceId: 7, name: "Sales" });
        });

        expect(left).toBe(true);
        expect(workspaceExit.leave).toHaveBeenCalledWith(7);
        expect(api.toastError).not.toHaveBeenCalled();
    });

    it("says so when a workspace exit's standing cannot be read again", async () => {
        await mountExits(workspaceExitFor(vi.fn(async () => "failed" as const)));

        let left = false;
        await act(async () => {
            left = await current().leave({ kind: "workspace", workspaceId: 7, name: "Sales" });
        });

        expect(left).toBe(true);
        expect(api.toastError).toHaveBeenCalledWith(enAccount.AccountSecurity.exitRefreshFailed);
    });

    it("catches up with a workspace the account no longer belongs to instead of refusing", async () => {
        const workspaceExit = workspaceExitFor(
            vi.fn(async (): Promise<PrivilegedMfaSettleOutcome | null> => {
                throw new ApiError("You are not a member of this workspace", 404, "RESOURCE_NOT_FOUND");
            }),
        );
        await mountExits(workspaceExit);

        let left = false;
        await act(async () => {
            left = await current().leave({ kind: "workspace", workspaceId: 7, name: "Sales" });
        });

        expect(left).toBe(true);
        expect(workspaceExit.reconcile).toHaveBeenCalledOnce();
        expect(current().refusals["workspace:7"]).toBeUndefined();
        expect(api.toastError).not.toHaveBeenCalled();
    });

    it("still counts a workspace left elsewhere when catching up fails, and says so", async () => {
        const workspaceExit = workspaceExitFor(
            vi.fn(async (): Promise<PrivilegedMfaSettleOutcome | null> => {
                throw new ApiError("You are not a member of this workspace", 404, "RESOURCE_NOT_FOUND");
            }),
            vi.fn(async (): Promise<PrivilegedMfaSettleOutcome | null> => {
                throw new Error("selection unavailable");
            }),
        );
        await mountExits(workspaceExit);

        let left = false;
        await act(async () => {
            left = await current().leave({ kind: "workspace", workspaceId: 7, name: "Sales" });
        });

        expect(left).toBe(true);
        expect(current().refusals["workspace:7"]).toBeUndefined();
        expect(api.toastError).toHaveBeenCalledWith(enAccount.AccountSecurity.exitRefreshFailed);
    });

    it("explains anything the server did not name generically", async () => {
        api.leaveOrganization.mockRejectedValueOnce(new ApiError("Conflict", 409, "SOMETHING_ELSE"));
        await mountExits();

        await act(async () => {
            await current().leave({ kind: "organization", orgId: 3, name: "Acme" });
        });

        expect(current().refusals["organization:3"]).toBe("OTHER");
    });

    it("cannot leave a workspace on a surface that has no way to", async () => {
        await mountExits();

        let left = true;
        await act(async () => {
            left = await current().leave({ kind: "workspace", workspaceId: 7, name: "Sales" });
        });

        expect(left).toBe(false);
        expect(api.leaveOrganization).not.toHaveBeenCalled();
    });
});
