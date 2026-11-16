/** @vitest-environment jsdom */
import { Children, isValidElement, type ComponentProps, type ReactElement, type ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { cleanup, getByRole, queryByRole, render, screen } from "@testing-library/react";
import { createTranslator, NextIntlClientProvider } from "next-intl";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import CustomFieldsPanel from "@/app/components/settings/CustomFieldsPanel";
import RecordsRenderView from "@/app/components/records/RecordsRenderView";
import RootLayout from "@/app/layout";
import { Pagination } from "@/components/ui/pagination";
import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import type { CustomFieldDefinition } from "@/app/lib/types";
import enCommon from "@/messages/en/common.json";
import jaCommon from "@/messages/ja/common.json";
import enWorkspace from "@/messages/en/workspace.json";
import jaWorkspace from "@/messages/ja/workspace.json";
import enContacts from "@/messages/en/contacts.json";
import jaContacts from "@/messages/ja/contacts.json";
import enRecords from "@/messages/en/records.json";
import jaRecords from "@/messages/ja/records.json";

const session = vi.hoisted(() => ({ locale: "en" as "en" | "ja" }));
const catalogues = { en: enCommon, ja: jaCommon };
vi.mock("next/headers", () => ({ headers: async () => new Headers() }));
vi.mock("next/server", () => ({ connection: async () => undefined }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock("next/font/google", () => ({
    Inter: () => ({ variable: "inter" }), Instrument_Serif: () => ({ variable: "instrument" }),
    Noto_Sans_JP: () => ({ variable: "noto-sans" }), Noto_Serif_JP: () => ({ variable: "noto-serif" }),
}));
vi.mock("next-intl/server", () => ({
    getLocale: async () => session.locale,
    getTranslations: async (namespace: "UI") => createTranslator({
        locale: session.locale, messages: catalogues[session.locale], namespace,
    }),
}));
vi.mock("@/app/hooks/useWorkspace", () => ({ useWorkspace: () => ({ activeWorkspaceId: 1 }) }));
vi.mock("@/app/hooks/usePermissions", () => ({
    usePermissionCheck: () => "granted", usePermissionsRefresh: vi.fn(),
}));
vi.mock("@/app/hooks/useApiErrorToast", () => ({ useApiErrorToast: () => vi.fn() }));
vi.mock("@/app/lib/api", () => ({ getCustomFields: async () => fields, deleteCustomField: vi.fn() }));
vi.mock("@/app/components/settings/CustomFieldDialog", () => ({ default: () => null }));
vi.mock("@/app/components/records/DeleteRecordDialog", () => ({ default: () => null }));

const fields: CustomFieldDefinition[] = ["Contract tier", "契約更新日"].map((label, index) => ({
    id: index + 1, workspaceId: 1, entityType: "company", fieldKey: `custom_${index}`, label,
    fieldType: "text", dataClassification: "standard", options: null, required: false,
    position: index, archived: false, createdAt: "2026-11-16T12:00:00Z", updatedAt: "2026-11-16T12:00:00Z",
}));

beforeEach(() => {
    vi.stubGlobal("matchMedia", (query: string): MediaQueryList => ({
        matches: false, media: query, onchange: null,
        addListener: vi.fn(), removeListener: vi.fn(),
        addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: () => true,
    }));
});

afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
});

function findToaster(node: ReactNode): ReactElement<ComponentProps<typeof Toaster>> | undefined {
    if (!isValidElement<{ children?: ReactNode }>(node)) return undefined;
    if (node.type === Toaster && isValidElement<ComponentProps<typeof Toaster>>(node)) return node;
    for (const child of Children.toArray(node.props.children)) {
        const found = findToaster(child);
        if (found) return found;
    }
    return undefined;
}

describe.each(["en", "ja"] as const)("localized accessibility (%s)", (locale) => {
    it("names pagination in the active language and preserves explicit names", () => {
        render(
            <NextIntlClientProvider locale={locale} messages={catalogues[locale]}>
                <Pagination />
                <Pagination aria-label={locale === "ja" ? "連絡先のページ切り替え" : "Contact pages"} />
            </NextIntlClientProvider>,
        );
        expect(screen.getByRole("navigation", { name: catalogues[locale].UI.pagination })).toBeDefined();
        expect(screen.getByRole("navigation", { name: locale === "ja" ? "連絡先のページ切り替え" : "Contact pages" })).toBeDefined();
        expect(screen.queryByRole("navigation", { name: "pagination" })).toBeNull();
    });

    it("passes the request language from the layout to the real Sonner region", async () => {
        session.locale = locale;
        const tree = await RootLayout({ children: null });
        const toaster = findToaster(tree);
        expect(toaster).toBeDefined();
        render(toaster);
        expect(screen.getByRole("region", {
            name: `${catalogues[locale].UI.notificationsRegion} alt+T`,
        })).toBeDefined();
        if (locale === "ja") expect(screen.queryByRole("region", { name: /Notifications/ })).toBeNull();
    });

    it("names custom-field actions with the field in that row", async () => {
        const messages = locale === "en" ? enWorkspace : jaWorkspace;
        const t = createTranslator({ locale, messages, namespace: "WorkspaceCustomFields" });
        render(
            <NextIntlClientProvider locale={locale} messages={{ ...catalogues[locale], ...messages }}>
                <TooltipProvider><CustomFieldsPanel /></TooltipProvider>
            </NextIntlClientProvider>,
        );
        for (const field of fields) {
            const action = await screen.findByRole("button", { name: t("fieldActions", { field: field.label }) });
            expect(action.closest("li")?.textContent).toContain(field.label);
        }
    });

    it("names contact select-all in the active language", () => {
        const messages = locale === "en" ? enRecords : jaRecords;
        const noun = (locale === "en" ? enContacts : jaContacts).ContactsBrowser.entityLabel;
        const t = createTranslator({ locale, messages, namespace: "RecordsRenderView" });
        const html = renderToStaticMarkup(
            <NextIntlClientProvider locale={locale} messages={{ ...catalogues[locale], ...messages }}>
                <TooltipProvider>
                    <RecordsRenderView data={[{ id: 1, name: "Buyer" }]} columns={[]} renderCard={() => null}
                        displayMode="table" selectedIds={new Set()} onSelectedIdsChange={vi.fn()}
                        entityLabel={noun} emptyState={null} />
                </TooltipProvider>
            </NextIntlClientProvider>,
        );
        const container = document.createElement("div");
        container.innerHTML = html;
        expect(getByRole(container, "checkbox", { name: t("selectAllAria", { entityLabel: noun }) })).toBeDefined();
        if (locale === "ja") expect(queryByRole(container, "checkbox", { name: "すべての contact を選択" })).toBeNull();
    });
});
