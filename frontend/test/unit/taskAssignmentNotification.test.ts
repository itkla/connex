import { createTranslator } from "next-intl";
import { describe, expect, it } from "vitest";

import { notificationContent } from "@/app/components/notifications/notificationContent";
import type { Notification } from "@/app/lib/types";
import en from "@/messages/en/notifications.json";
import ja from "@/messages/ja/notifications.json";

describe.each(["en", "ja"] as const)("task assignment notification in %s", (locale) => {
    it("localizes the task assignment with the actor and task label", () => {
        const messages: Record<string, string> = locale === "en" ? en.Notifications : ja.Notifications;
        const t = createTranslator({ locale, messages });
        const notification: Notification = {
            id: 1, workspaceId: 7, type: "task.assigned", category: "task", severity: "info", templateVersion: 1,
            title: "Task assigned to you", actorLabel: "Aiko", sourceLabel: "Call customer", triggeredAt: "", createdAt: "", updatedAt: "",
        };
        const content = notificationContent(notification, t, locale);
        expect(content.title).toBe(locale === "en" ? en.Notifications.taskAssignedTitle : ja.Notifications.taskAssignedTitle);
        expect(content.body).toContain("Aiko");
        expect(content.body).toContain("Call customer");
    });
});
