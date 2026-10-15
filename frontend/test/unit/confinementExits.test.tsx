// @vitest-environment jsdom
import { act, useEffect } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/app/lib/api";
import type { ConfinementExits } from "@/app/hooks/useConfinementExits";

const api = vi.hoisted(() => ({ leaveOrganization: vi.fn<(orgId: number) => Promise<void>>() }));

vi.mock("@/app/lib/api", async (importOriginal) => ({
    ...await importOriginal<typeof import("@/app/lib/api")>(),
    leaveOrganization: api.leaveOrganization,
}));

let container: HTMLDivElement;
let root: Root;
let latest: ConfinementExits | null = null;

async function mountExits(leaveWorkspace?: (workspaceId: number) => Promise<void>) {
    const { useConfinementExits } = await import("@/app/hooks/useConfinementExits");
    function Probe() {
        const exits = useConfinementExits(leaveWorkspace);
        useEffect(() => {
            latest = exits;
        });
        return null;
    }
    await act(async () => root.render(<Probe />));
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
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
});

describe("leaving what confines a privileged account", () => {
    it("leaves an organization and forgets an earlier refusal of it", async () => {
        api.leaveOrganization
            .mockRejectedValueOnce(new ApiError("Last owner", 400, "ORG_LAST_OWNER"))
            .mockResolvedValueOnce(undefined);
        await mountExits();
        const target = { kind: "organization" as const, orgId: 3, name: "Acme" };

        let left = true;
        await act(async () => {
            left = await current().leave(target);
        });
        expect(left).toBe(false);
        expect(current().refusals["organization:3"]).toBe("ORG_LAST_OWNER");

        await act(async () => {
            left = await current().leave(target);
        });
        expect(left).toBe(true);
        expect(api.leaveOrganization).toHaveBeenLastCalledWith(3);
        expect(current().refusals["organization:3"]).toBeUndefined();
        expect(current().leavingKey).toBeNull();
    });

    it.each([
        "WORKSPACE_LAST_OWNER",
        "WORKSPACE_RECOVERY_OWNER_REQUIRED",
        "SOLE_NAMED_DOCUMENT_APPROVER",
    ])("keeps the server's %s refusal of a workspace exit", async (code) => {
        const leaveWorkspace = vi.fn(async () => {
            throw new ApiError("Refused", code === "SOLE_NAMED_DOCUMENT_APPROVER" ? 409 : 400, code);
        });
        await mountExits(leaveWorkspace);

        await act(async () => {
            await current().leave({ kind: "workspace", workspaceId: 7, name: "Sales" });
        });

        expect(leaveWorkspace).toHaveBeenCalledWith(7);
        expect(current().refusals["workspace:7"]).toBe(code);
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
