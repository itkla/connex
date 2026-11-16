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

    it("distinguishes AI use from organization settings in every privileged role summary", () => {
        for (const role of ["owner", "admin"]) {
            expect(t(`builtInRoles.${role}.description`)).toContain(locale === "en"
                ? "AI settings require organization administrator access"
                : "AI設定には組織管理者の権限が必要");
        }
        expect(t("builtInRoles.admin.description")).toContain(locale === "en" ? "use AI features" : "AI機能を利用");
        expect(t("permissionLabels.AI_USE.description")).toContain(locale === "en"
            ? "AI settings require organization administrator access"
            : "AI設定には組織管理者の権限が必要");
    });

    it("uses the glossary for Deals and Notes throughout role copy", () => {
        expect(t("permissionGroups.DEAL")).toBe(locale === "en" ? "Deals" : "案件");
        expect(t("permissionGroups.NOTE")).toBe(locale === "en" ? "Notes" : "メモ");
        expect(t("builtInRoles.member.description")).toContain(locale === "en" ? "deals" : "案件");
        expect(t("builtInRoles.member.description")).toContain(locale === "en" ? "notes" : "メモ");
        if (locale === "ja") expect(JSON.stringify(ja.WorkspaceRoles)).not.toMatch(/商談|ノート/);
    });

    it("states workspace credential scope and separates sequence authoring from sending", () => {
        expect(t("permissionLabels.API_CREDENTIAL_MANAGE.action")).not.toMatch(/personal|個人用/);
        expect(t("permissionLabels.API_CREDENTIAL_MANAGE.description")).toContain(locale === "en"
            ? "revoke workspace credentials"
            : "ワークスペース内の認証情報の確認・失効");
        expect(t("permissionLabels.SEQUENCE_MANAGE.description")).not.toMatch(/manage enrollment|登録を管理/);
        expect(t("permissionLabels.SEQUENCE_MANAGE.description")).toContain(locale === "en"
            ? "does not grant permission to send campaigns"
            : "キャンペーンを送信する権限は付与されません");
        expect(t("permissionLabels.DOCUMENT_MANAGE.description")).toContain(locale === "en"
            ? "Editing deal documents needs permission to edit deals"
            : "案件の文書の編集には案件の編集権限");
    });

    it("names additional authority for protected deletion and shared chats", () => {
        expect(t("permissionLabels.DEAL_DELETE.description")).toContain(locale === "en"
            ? "built-in Owner or Admin role"
            : "組み込みのオーナーまたは管理者ロール");
        expect(t("permissionLabels.REPORT_DELETE.description")).toContain(locale === "en"
            ? "another member's"
            : "ほかのメンバー");
        expect(t("permissionLabels.COMMENT_MODERATE.description")).toContain(locale === "en"
            ? "permission to add comments"
            : "コメントの追加権限");
        expect(t("permissionLabels.AI_SESSION_SHARE.description")).toContain(locale === "en"
            ? "You and participants"
            : "自分と参加者の双方");
    });

    it("never humanizes an unknown permission into product copy", () => {
        const groups = groupPermissions(["INTERNAL_SECRET_OPERATION"], t);
        expect(groups[0].label).toBe(t("unknownPermissionGroup"));
        expect(groups[0].items[0].action).toBe(t("unknownPermission"));
        expect(groups[0].items[0].description).toBe(t("unknownPermissionDescription"));
    });
});
