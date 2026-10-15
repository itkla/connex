import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import enOrganization from "@/messages/en/organization.json";
import enWorkspace from "@/messages/en/workspace.json";
import jaOrganization from "@/messages/ja/organization.json";
import jaWorkspace from "@/messages/ja/workspace.json";

function source(relative: string): string {
    return readFileSync(path.resolve(process.cwd(), relative), "utf8");
}

describe("the passkey-not-approved badge on member rows", () => {
    it.each([
        ["app/components/settings/MembersPanel.tsx"],
        ["app/components/organization/OrgMembersPanel.tsx"],
    ])("%s shows it only when the server says true, never for an omitted field", (file) => {
        const panel = source(file);

        expect(panel).toMatch(/member\.passkeyApprovalNeeded === true && \(/);
        expect(panel).toContain('{t("passkeyNotApproved")}');
        expect(panel).toContain('title={t("passkeyNotApprovedHint")}');
    });

    it("re-reads the roster after a role change so the badge follows the new role", () => {
        const panel = source("app/components/settings/MembersPanel.tsx");
        const changeRole = panel.slice(panel.indexOf("const changeRole = async"), panel.indexOf("const withdrawOwnershipOffer = async"));
        const assignCustom = panel.slice(panel.indexOf("const assignCustom = async"), panel.indexOf("const confirmRemove = async"));

        expect(changeRole.match(/void refreshMembers\(\);/g) ?? []).toHaveLength(2);
        expect(assignCustom).toContain("void refreshMembers();");
        expect(panel).toMatch(/if \(refreshId === membersRefresh\.current\) setMembers\(loaded\);/);
        const confirmRemove = panel.slice(panel.indexOf("const confirmRemove = async"), panel.indexOf("const copyInviteLink"));
        const sendInvite = panel.slice(panel.indexOf("const sendInvite = async"));
        expect(confirmRemove.indexOf("membersRefresh.current += 1;"))
            .toBeLessThan(confirmRemove.indexOf("setMembers((prev) => prev.filter"));
        expect(confirmRemove).toContain("membersRefresh.current += 1;");
        expect(sendInvite).toMatch(/membersRefresh\.current \+= 1;\s+setMembers\(\(prev\) => \[\.\.\.prev\.filter/);
    });

    it("reports a roster that could not be read again through the shared error toast", () => {
        const workspacePanel = source("app/components/settings/MembersPanel.tsx");
        const refresh = workspacePanel.slice(
            workspacePanel.indexOf("const refreshMembers = useCallback"),
            workspacePanel.indexOf("}, [showApiError, workspaceId]);"),
        );
        expect(refresh).toMatch(/catch \(err\) \{\s+if \(refreshId === membersRefresh\.current\) \{\s+showApiError\(err, "membersRefreshFailed", "membersRefreshFailedDescription"\);/);
        expect(workspacePanel).toMatch(/useEffect\(\(\) => \{\s+const generation = membersRefresh;\s+return \(\) => \{\s+generation\.current \+= 1;\s+\};\s+\}, \[workspaceId\]\);/);

        const organizationPanel = source("app/components/organization/OrgMembersPanel.tsx");
        const changeRole = organizationPanel.slice(
            organizationPanel.indexOf("async function changeRole"),
            organizationPanel.indexOf("async function confirmRemove"),
        );
        expect(changeRole).toContain("(error: unknown) => ({ ok: false as const, error }),");
        expect(changeRole.indexOf('toastSuccess(t("roleUpdatedToast"));')).toBeLessThan(changeRole.indexOf(
            'if (!reloaded.ok) showApiError(reloaded.error, "membersRefreshFailed", "membersRefreshFailedDescription");',
        ));
    });

    it("has its copy in both languages for both lists", () => {
        for (const messages of [enWorkspace.WorkspaceMembers, jaWorkspace.WorkspaceMembers,
            enOrganization.OrgMembers, jaOrganization.OrgMembers]) {
            expect(messages.passkeyNotApproved).toBeTruthy();
            expect(messages.passkeyNotApprovedHint).toBeTruthy();
            expect(messages.membersRefreshFailed).toBeTruthy();
            expect(messages.membersRefreshFailedDescription).toBeTruthy();
        }
    });
});
