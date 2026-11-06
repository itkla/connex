import { readFileSync } from "node:fs";
import { createTranslator } from "next-intl";
import { describe, expect, it } from "vitest";

import { groupPermissions } from "@/app/components/settings/permissions";
import en from "@/messages/en/workspace.json";
import ja from "@/messages/ja/workspace.json";

type MessageTree = { [key: string]: string | MessageTree };

const permissionSource = readFileSync("../backend/src/main/java/ooo/klae/connex/backend/tenant/Permission.java", "utf8");
const catalog = [...permissionSource.matchAll(/^    ([A-Z]+(?:_[A-Z]+)+)[,;]/gm)]
    .map((match) => match[1])
    .filter((permission) => permission !== "SSO_MANAGE" && permission !== "WORKSPACE_DELETE");

describe.each(["en", "ja"] as const)("permission explanations in %s", (locale) => {
    const messages: MessageTree = locale === "en" ? en : ja;
    const t = createTranslator({ locale, messages, namespace: "WorkspaceRoles" });

    it("covers the entire grantable backend catalog with labels and descriptions", () => {
        const groups = groupPermissions(catalog, t);
        const items = groups.flatMap((group) => group.items);
        expect(catalog.length).toBeGreaterThan(50);
        expect(items.map((item) => item.permission)).toHaveLength(catalog.length);
        for (const group of groups) {
            expect(group.label).not.toMatch(/^[A-Z_]+$/);
            for (const item of group.items) {
                expect(t.has(`permissionLabels.${item.permission}.action`)).toBe(true);
                expect(t.has(`permissionLabels.${item.permission}.description`)).toBe(true);
                expect(item.action).not.toBe(item.permission);
                expect(item.description).toMatch(/[.。]$/);
                if (locale === "ja") expect(`${group.label}${item.action}${item.description}`).toMatch(/[ぁ-んァ-ヶ一-龯]/);
            }
        }
    });

    it("uses Contacts and Workflows vocabulary and describes sensitive limits", () => {
        const groups = groupPermissions(["PERSON_CREATE", "RULE_MANAGE", "ATTACHMENT_QUARANTINE_MANAGE", "AI_SESSION_ADMIN"], t);
        expect(groups.map((group) => group.label)).toEqual(locale === "en"
            ? ["Contacts", "Workflows", "Files", "Ask Connex and AI features"]
            : ["連絡先", "ワークフロー", "ファイル", "Ask ConnexとAI機能"]);
        expect(groups[2].items[0].description).toContain(locale === "en" ? "does not grant access" : "閲覧する権限は付与されません");
        expect(groups[3].items[0].description).toContain(locale === "en" ? "does not allow changing" : "内容の変更");
    });

    it("never humanizes an unknown permission into product copy", () => {
        const groups = groupPermissions(["INTERNAL_SECRET_OPERATION"], t);
        expect(groups[0].label).toBe(t("unknownPermissionGroup"));
        expect(groups[0].items[0].action).toBe(t("unknownPermission"));
        expect(groups[0].items[0].description).toBe(t("unknownPermissionDescription"));
    });
});
