import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import enComments from "@/messages/en/comments.json";
import enRecords from "@/messages/en/records.json";
import jaComments from "@/messages/ja/comments.json";
import jaRecords from "@/messages/ja/records.json";

describe("comment delete grammar", () => {
    it("uses the shared record delete confirmation with comment-specific consequences", () => {
        const source = readFileSync(
            path.join(process.cwd(), "app/components/records/comments/CommentsSection.tsx"),
            "utf8",
        );

        expect(source).toContain("<DeleteRecordDialog<RecordComment>");
        expect(source).toContain("details={<p");
        expect(
            existsSync(path.join(process.cwd(), "app/components/records/comments/CommentDeleteDialog.tsx")),
        ).toBe(false);
    });

    it("keeps one irreversible-action sentence in each locale", () => {
        expect(enRecords.RecordsDeleteDialog.descriptionSingle).toContain("This can't be undone.");
        expect(jaRecords.RecordsDeleteDialog.descriptionSingle).toContain("この操作は取り消せません。");
        expect(enComments.Comments.deleteConsequence).not.toMatch(/undone|restored/i);
        expect(jaComments.Comments.deleteConsequence).not.toMatch(/取り消|元に戻/);
        expect(enComments.Comments).not.toHaveProperty("deleteTitle");
        expect(enComments.Comments).not.toHaveProperty("deleteBody");
        expect(enComments.Comments).not.toHaveProperty("confirmDelete");
        expect(jaComments.Comments).not.toHaveProperty("deleteTitle");
        expect(jaComments.Comments).not.toHaveProperty("deleteBody");
        expect(jaComments.Comments).not.toHaveProperty("confirmDelete");
    });
});
