import type { PropsWithChildren } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { NextIntlClientProvider } from "next-intl";
import { describe, expect, it, vi } from "vitest";

import AuditDiagnostics from "@/app/components/settings/AuditDiagnostics";
import type { AuditLogEntry } from "@/app/lib/types";
import englishAdmin from "@/messages/en/admin.json";
import englishSettings from "@/messages/en/settings.json";
import japaneseAdmin from "@/messages/ja/admin.json";
import japaneseSettings from "@/messages/ja/settings.json";

vi.mock("@/app/hooks/useWorkspace", () => ({
    useWorkspace: () => ({ activeWorkspace: { id: 1, name: "Test workspace" } }),
}));

vi.mock("@/app/hooks/usePermissions", () => ({
    usePermissionCheck: () => "granted",
}));

vi.mock("@/app/hooks/useSectionArrival", () => ({
    useSectionArrival: () => ({ register: () => undefined, arrived: null }),
}));

vi.mock("@/app/components/motion/Rise", () => ({
    default: ({ children }: PropsWithChildren) => <div>{children}</div>,
}));

vi.mock("@/app/components/PageHeader", () => ({
    PageHeader: ({ title }: { title: string }) => <h1>{title}</h1>,
}));

vi.mock("@/app/components/diagnostics/DiagnosticsPanel", () => ({
    default: () => <div data-testid="diagnostics">Diagnostics available</div>,
}));

const TARGETLESS_ENTRY: AuditLogEntry = {
    id: 1,
    action: "invite_link.create",
    actorLabel: "Workspace operator",
    createdAt: "2026-11-04T08:00:00Z",
};

describe("audit rows with omitted optional fields", () => {
    it.each([
        { locale: "en", admin: englishAdmin, settings: englishSettings },
        { locale: "ja", admin: japaneseAdmin, settings: japaneseSettings },
    ])("keeps the audit log and its diagnostics sibling reachable in $locale", ({ locale, admin, settings }) => {
        const html = renderToStaticMarkup(
            <NextIntlClientProvider locale={locale} messages={{ ...admin, ...settings }} timeZone="UTC">
                <AuditDiagnostics audit={{ kind: "loaded", entries: [TARGETLESS_ENTRY] }} pageSize={200} />
            </NextIntlClientProvider>,
        );

        expect(html).toContain(TARGETLESS_ENTRY.actorLabel);
        expect(html).toContain('data-testid="diagnostics"');
        expect(html).not.toContain("undefined");
    });
});
