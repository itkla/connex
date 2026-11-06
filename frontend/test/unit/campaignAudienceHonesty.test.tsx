import { act, type PropsWithChildren, type ReactNode, type ComponentProps } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";

import type EditCampaignSheet from "@/app/components/marketing/campaigns/EditCampaignSheet";
import AudienceEstimatePanel from "@/app/components/marketing/campaigns/AudienceEstimatePanel";
import CampaignDelivery from "@/app/components/marketing/campaigns/CampaignDelivery";
import CampaignDetail from "@/app/components/marketing/campaigns/CampaignDetail";
import CampaignExportPanel from "@/app/components/marketing/campaigns/CampaignExportPanel";
import type {
    Campaign,
    CampaignAudience,
    CampaignAudienceEstimate,
    CampaignAudienceExport,
    CampaignAudienceSnapshotSummary,
    CampaignEngagement,
    CampaignMessage,
} from "@/app/lib/types";
import enCampaigns from "@/messages/en/campaigns.json";
import jaCampaigns from "@/messages/ja/campaigns.json";
import enRecords from "@/messages/en/records.json";
import jaRecords from "@/messages/ja/records.json";
import {
    installInteractiveDocument,
    type InteractiveElement,
} from "@/test/unit/helpers/interactiveDocument";

const localeState = vi.hoisted<{ locale: "en" | "ja"; zone: string }>(() => ({ locale: "en", zone: "UTC" }));
const editor = vi.hoisted<{ current: ComponentProps<typeof EditCampaignSheet> | null }>(() => ({ current: null }));

const { reconcileCampaignExportMock, setCampaignAudienceMock, updateCampaignMock } = vi.hoisted(() => ({
    updateCampaignMock: vi.fn(),
    reconcileCampaignExportMock: vi.fn(async (): Promise<unknown> => undefined),
    setCampaignAudienceMock: vi.fn(async () => {}),
}));

vi.mock("next/navigation", () => ({
    useRouter: () => ({
        back: vi.fn(),
        push: vi.fn(),
        refresh: vi.fn(),
    }),
}));

vi.mock("next-intl", async () => {
    const [{ default: en }, { default: ja }] = await Promise.all([
        import("@/messages/en/campaigns.json"),
        import("@/messages/ja/campaigns.json"),
    ]);
    const [{ default: enRecords }, { default: jaRecords }] = await Promise.all([
        import("@/messages/en/records.json"), import("@/messages/ja/records.json"),
    ]);
    const catalogs = { en: { ...en, ...enRecords }, ja: { ...ja, ...jaRecords } };
    return {
        useTimeZone: () => localeState.zone, useLocale: () => localeState.locale,
        useTranslations: (namespace: string) => (
            key: string,
            values?: Record<string, string | number>,
        ) => {
            const path = `${namespace}.${key}`.split(".");
            let message: unknown = catalogs[localeState.locale];
            for (const part of path) {
                if (typeof message !== "object" || message === null || !(part in message)) {
                    if (namespace !== "CampaignAudience" && namespace !== "CampaignExports") {
                        return `${namespace}.${key}`;
                    }
                    throw new Error(`Missing ${localeState.locale} translation: ${namespace}.${key}`);
                }
                message = (message as Record<string, unknown>)[part];
            }
            if (typeof message !== "string") {
                throw new Error(`Translation is not a string: ${namespace}.${key}`);
            }
            return Object.entries(values ?? {}).reduce(
                (rendered, [name, value]) => rendered.replaceAll(`{${name}}`, String(value)),
                message,
            );
        },
    };
});

vi.mock("@/app/lib/api", async (importOriginal) => {
    const actual = await importOriginal<typeof import("@/app/lib/api")>();
    return {
        ...actual,
        getSegmentFields: vi.fn(async () => ({ industries: [], tags: [] })),
        reconcileCampaignExport: reconcileCampaignExportMock,
        setCampaignAudience: setCampaignAudienceMock,
        updateCampaign: updateCampaignMock,
    };
});

vi.mock("@/app/components/overview/analytics/Panel", () => ({
    default: ({ action, children, title }: PropsWithChildren<{ action?: ReactNode; title: string }>) => (
        <section><h2>{title}</h2>{action}{children}</section>
    ),
}));

vi.mock("@/app/components/PageShell", () => ({
    PageShell: ({ children }: PropsWithChildren) => <main>{children}</main>,
}));

vi.mock("@/app/components/motion/Rise", () => ({
    default: ({ children }: PropsWithChildren) => <div>{children}</div>,
}));

vi.mock("@/components/ui/tabs", () => ({
    Tabs: ({ children }: PropsWithChildren) => <div>{children}</div>,
    TabsContent: ({ children }: PropsWithChildren) => <div>{children}</div>,
    TabsList: ({ children }: PropsWithChildren) => <div>{children}</div>,
    TabsTrigger: ({ children }: PropsWithChildren) => <button>{children}</button>,
}));

vi.mock("@/components/ui/select", async () => {
    const React = await import("react");
    type SelectContextValue = {
        disabled: boolean;
        onValueChange?: (value: string) => void;
    };
    const SelectContext = React.createContext<SelectContextValue>({ disabled: false });
    return {
        Select: ({
            children,
            disabled = false,
            onValueChange,
        }: PropsWithChildren<{
            disabled?: boolean;
            onValueChange?: (value: string) => void;
            value?: string;
        }>) => (
            <SelectContext.Provider value={{ disabled, onValueChange }}>
                <div>{children}</div>
            </SelectContext.Provider>
        ),
        SelectContent: ({ children }: PropsWithChildren) => <div>{children}</div>,
        SelectItem: ({ children, value }: PropsWithChildren<{ value: string }>) => {
            const context = React.useContext(SelectContext);
            return (
                <button
                    type="button"
                    data-select-value={value}
                    disabled={context.disabled}
                    onClick={() => context.onValueChange?.(value)}
                >
                    {children}
                </button>
            );
        },
        SelectTrigger: ({ children, id }: PropsWithChildren<{ id?: string }>) => (
            <span id={id}>{children}</span>
        ),
        SelectValue: () => null,
    };
});

vi.mock("@/components/ui/responsive-dialog", async () => {
    const React = await import("react");
    const CloseContext = React.createContext<(() => void) | null>(null);
    const Passthrough = ({ children }: PropsWithChildren) => <>{children}</>;
    return {
        ResponsiveDialog: ({
            children,
            open,
            onOpenChange,
        }: PropsWithChildren<{
            open?: boolean;
            onOpenChange?: (open: boolean) => void;
        }>) => (
            <CloseContext.Provider value={() => onOpenChange?.(false)}>
                {open ? children : null}
            </CloseContext.Provider>
        ),
        ResponsiveDialogClose: ({ children }: PropsWithChildren) => {
            const close = React.useContext(CloseContext);
            return <span onClick={() => close?.()}>{children}</span>;
        },
        ResponsiveDialogContent: Passthrough,
        ResponsiveDialogDescription: Passthrough,
        ResponsiveDialogFooter: Passthrough,
        ResponsiveDialogHeader: Passthrough,
        ResponsiveDialogTitle: Passthrough,
    };
});

vi.mock("@/app/components/records/SegmentBuilder", () => ({
    EMPTY_DEFINITION: { match: "all", conditions: [] },
    default: () => <div>segment-builder</div>,
}));

vi.mock("@/app/components/marketing/campaigns/NewMessageDialog", () => ({ default: () => null }));
vi.mock("@/app/components/marketing/campaigns/CampaignEngagement", () => ({ default: () => null }));
vi.mock("@/app/components/marketing/campaigns/EditCampaignSheet", () => ({ default: (props: ComponentProps<typeof EditCampaignSheet>) => { editor.current = props; return null; } }));
vi.mock("@/app/components/records/DeleteRecordDialog", () => ({ default: () => null }));
vi.mock("@/app/components/marketing/campaigns/CampaignStatusBadge", () => ({ default: () => null }));
vi.mock("@/app/components/dashboard/CountUp", () => ({
    default: ({ value }: { value: number }) => <span>{value}</span>,
}));
vi.mock("@/app/hooks/useNavTrail", () => ({ CrumbLabel: () => null }));
vi.mock("@/app/hooks/useApiErrorToast", () => ({ useApiErrorToast: () => vi.fn() }));
vi.mock("@/app/lib/toast", () => ({ toastError: vi.fn(), toastSuccess: vi.fn() }));

const CAMPAIGN = {
    id: 31,
    name: "Product updates",
    objective: null,
    type: "email",
    status: "draft",
    ownerUserId: null,
    budgetAmount: null,
    budgetCurrency: null,
    startAt: null,
    endAt: null,
    parentCampaignId: null,
    createdById: 9,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
} satisfies Campaign;

const SNAPSHOT = {
    version: 1,
    recordType: "person",
    channel: "email",
    purpose: "product_update",
    estimatedIncluded: 2,
    excludedTotal: 1,
    excludedNoAddress: 0,
    excludedConsent: 1,
    excludedSuppressed: 0,
    excludedRestricted: 0,
    createdById: 9,
    createdAt: "2026-01-01T00:00:00Z",
} satisfies CampaignAudienceSnapshotSummary;

const SMS_SNAPSHOT = {
    ...SNAPSHOT,
    version: 2,
    channel: "sms",
    purpose: "marketing",
    estimatedIncluded: 3,
} satisfies CampaignAudienceSnapshotSummary;

const EMAIL_MESSAGE = {
    id: 41,
    campaignId: CAMPAIGN.id,
    channel: "email",
    name: "Product update email",
    status: "draft",
    createdById: 9,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    revisions: [{
        version: 1,
        locale: "en",
        subject: "Product update",
        bodyHtml: "<p>Update</p>",
        bodyText: "Update",
        createdAt: "2026-01-01T00:00:00Z",
    }],
} satisfies CampaignMessage;

const AUDIENCE = {
    campaignId: 31,
    recordType: "person",
    definition: {
        match: "all",
        conditions: [{ type: "field", field: "name", op: "starts_with", value: "Product" }],
    },
    mode: "live",
    channel: "email",
    purpose: "product_update",
    updatedAt: "2026-01-01T00:00:00Z",
} satisfies CampaignAudience;

const COMPLETED_EXPORT = {
    id: 71,
    campaignId: CAMPAIGN.id,
    snapshotId: 91,
    connector: "http_list",
    externalListId: "product-updates",
    status: "completed",
    reconciliationRequired: false,
    totalMembers: 2,
    pushedCount: 1,
    failedCount: 1,
    detailedCountsKnown: true,
    detailedCountsAvailable: true,
    createdById: 9,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:01Z",
} satisfies CampaignAudienceExport;

function detailHtml(
    audienceUnavailable: boolean,
    snapshots: CampaignAudienceSnapshotSummary[] = [SNAPSHOT],
    engagement: CampaignEngagement | null = null,
    failures: Pick<ComponentProps<typeof CampaignDetail>, "deliveryFailure" | "exportsFailure"> = {},
): string {
    return renderToStaticMarkup(
        <CampaignDetail
            {...failures}
            campaign={CAMPAIGN}
            initialAudience={null}
            audienceUnavailable={audienceUnavailable}
            initialSnapshots={snapshots}
            initialMessages={[]}
            initialSends={[]}
            initialExports={[]}
            initialEngagement={engagement}
            access={{ manage: true, send: true, consent: true }}
            snapshotsRestricted={false}
            deliveryAvailability="enabled"
        />,
    );
}

function requiredElement(
    elements: readonly InteractiveElement[],
    predicate: (element: InteractiveElement) => boolean,
    label: string,
): InteractiveElement {
    const element = elements.findLast(predicate);
    if (!element) throw new Error(`${label} did not render`);
    return element;
}

afterEach(() => {
    localeState.locale = "en";
    vi.clearAllMocks();
    vi.unstubAllGlobals();
});

describe("campaign date inputs", () => {
    it.each([
        { zone: "Asia/Tokyo", original: "2026-11-05T14:30:27", civil: "2026-11-05T23:30", edited: "2026-11-05T22:30", utc: "2026-11-05T13:30:00" },
        { zone: "America/New_York", original: "2026-11-01T06:30:27", civil: "2026-11-01T01:30", edited: "2026-11-01T02:30", utc: "2026-11-01T07:30:00" },
    ])("round-trips campaign windows in $zone without double conversion", async ({ zone, original, civil, edited, utc }) => {
        localeState.zone = zone;
        const campaign = { ...CAMPAIGN, startAt: original, endAt: original };
        updateCampaignMock.mockResolvedValue(campaign);
        const interactive = installInteractiveDocument();
        const { createRoot } = await import("react-dom/client");
        const root = createRoot(interactive.container);
        try {
            await act(async () => root.render(<CampaignDetail campaign={campaign} initialAudience={null}
                audienceUnavailable={false} initialSnapshots={[]} initialMessages={[]} initialSends={[]}
                initialExports={[]} initialEngagement={null} access={{ manage: true, send: true, consent: true }}
                snapshotsRestricted={false} deliveryAvailability="enabled" />));
            const edit = requiredElement(interactive.elements, (element) => element.tagName === "BUTTON"
                && element.textContent === enCampaigns.CampaignDetail.edit, "Edit campaign");
            await act(async () => interactive.dispatch("click", edit));
            const initial = editor.current;
            if (!initial) throw new Error("Campaign editor was not rendered");
            expect(initial.payload.startAt).toBe(civil);
            expect(initial.payload.endAt).toBe(civil);
            await act(async () => initial.onSubmit());
            expect(updateCampaignMock).toHaveBeenLastCalledWith(campaign.id, expect.objectContaining({ startAt: original, endAt: original }));
            await act(async () => initial.setPayload((value) => ({ ...value, startAt: edited, endAt: edited })));
            const changed = editor.current;
            if (!changed) throw new Error("Campaign editor was not rendered");
            await act(async () => changed.onSubmit());
            expect(updateCampaignMock).toHaveBeenLastCalledWith(campaign.id, expect.objectContaining({ startAt: utc, endAt: utc }));
        } finally {
            await act(async () => root.unmount());
            localeState.zone = "UTC";
            updateCampaignMock.mockClear();
        }
    });
});

describe("campaign audience labels and unavailable state", () => {
    it("hides failed delivery and export collections instead of showing empty states in both locales", () => {
        for (const locale of ["en", "ja"] as const) {
            localeState.locale = locale;
            const catalog = locale === "en" ? enCampaigns : jaCampaigns;
            const records = locale === "en" ? enRecords : jaRecords;
            const html = detailHtml(false, [], null, {
                deliveryFailure: { kind: "failed", status: 503 },
                exportsFailure: { kind: "failed", status: 403 },
            });
            const failedTitle = records.RecordListState.sectionTitle.replace("{section}", catalog.CampaignDetail.tabDelivery);
            const deniedTitle = records.RecordListState.sectionDeniedTitle.replace("{section}", catalog.CampaignDetail.tabExports);
            expect(html).toContain(failedTitle.replaceAll("'", "&#x27;"));
            expect(html).toContain(deniedTitle.replaceAll("'", "&#x27;"));
            expect(html).not.toContain(catalog.CampaignMessages.empty);
            expect(html).not.toContain(catalog.CampaignExports.empty);
            const recovered = detailHtml(false, []);
            expect(recovered).toContain(catalog.CampaignMessages.empty);
            expect(recovered).toContain(catalog.CampaignExports.empty);
        }
    });

    it("labels draft send membership as prepared recipients in both locales", () => {
        for (const locale of ["en", "ja"] as const) {
            localeState.locale = locale;
            const counts = {
                preparedRecipients: 6, dispatched: 0, delivered: 0, bounced: 0, complained: 0,
                unsubscribed: 0, failed: 0, skipped: 0, skipReasons: {}, eventCounts: {},
                deliveryReceiptsAvailable: false, deliveryRate: null, bounceRate: null, complaintRate: null,
            };
            const html = detailHtml(false, [SNAPSHOT], {
                ...counts,
                campaignId: CAMPAIGN.id,
                channels: [],
                sends: [{ ...counts, sendId: 11, status: "draft", channel: "email" }],
            });
            const catalog = locale === "en" ? enCampaigns : jaCampaigns;

            expect(html).toMatch(new RegExp(`${catalog.CampaignDetail.preparedRecipients}</span><span[^>]*>6</span>`));
            expect(html).toContain(catalog.CampaignDetail.preparedRecipientsHint);
            expect(html).not.toContain(locale === "en" ? "Recipients reached" : ">到達数<");
        }
    });

    it("labels a custom-purpose estimate with its channel and exact purpose", () => {
        const estimate = {
            channel: "email",
            purpose: "product_update",
            estimatedIncluded: 2,
            excludedNoAddress: 0,
            excludedConsent: 1,
            excludedSuppressed: 0,
            excludedRestricted: 0,
            excludedTotal: 1,
            sampleLabels: [],
        } satisfies CampaignAudienceEstimate;

        const html = renderToStaticMarkup(
            <AudienceEstimatePanel estimate={estimate} recordType="person" />,
        );

        expect(html).toContain("Counts for Email · Purpose: product_update");
        expect(html).toContain("Explicitly opted out of product_update on Email.");
        expect(html).not.toContain("Purpose: marketing");
    });

    it("labels each snapshot row with its frozen channel and purpose", () => {
        const html = detailHtml(false, [SNAPSHOT, SMS_SNAPSHOT]);
        const snapshotRows = html.match(/<li\b[^>]*>.*?<\/li>/g) ?? [];
        const smsSnapshotRow = snapshotRows.find((row) => row.includes(">v2<"));

        expect(smsSnapshotRow).toContain(">SMS<");
        expect(smsSnapshotRow).toContain("Purpose: marketing");
    });

    it("renders the channel-specific no-address copy from both locale catalogs", () => {
        const catalogs = { en: enCampaigns, ja: jaCampaigns };
        for (const locale of ["en", "ja"] as const) {
            localeState.locale = locale;
            for (const channel of ["email", "sms"] as const) {
                const estimate = {
                    channel,
                    purpose: "marketing",
                    estimatedIncluded: 0,
                    excludedNoAddress: 1,
                    excludedConsent: 0,
                    excludedSuppressed: 0,
                    excludedRestricted: 0,
                    excludedTotal: 1,
                    sampleLabels: [],
                } satisfies CampaignAudienceEstimate;
                const html = renderToStaticMarkup(
                    <AudienceEstimatePanel estimate={estimate} recordType="person" />,
                );
                const suffix = channel === "sms" ? "Sms" : "Email";
                const messages = catalogs[locale].CampaignAudience;

                expect(html).toContain(messages[`excludedNoAddress${suffix}`]);
                expect(html).toContain(messages[`noAddress${suffix}Hint`]);
            }
        }
    });

    it("sends the selected SMS channel in the audience save payload", async () => {
        const interactive = installInteractiveDocument();
        const { createRoot } = await import("react-dom/client");
        const root = createRoot(interactive.container);
        await act(async () => {
            root.render(
                <CampaignDetail
                    campaign={CAMPAIGN}
                    initialAudience={AUDIENCE}
                    audienceUnavailable={false}
                    initialSnapshots={[SNAPSHOT]}
                    initialMessages={[]}
                    initialSends={[]}
                    initialExports={[]}
                    initialEngagement={null}
                    access={{ manage: true, send: true, consent: true }}
                    snapshotsRestricted={false}
                    deliveryAvailability="enabled"
                />,
            );
        });

        const smsOption = requiredElement(
            interactive.elements,
            (element) => element.tagName === "BUTTON"
                && element.getAttribute("data-select-value") === "sms",
            "SMS audience option",
        );
        await act(async () => {
            interactive.dispatch("click", smsOption);
        });
        const save = requiredElement(
            interactive.elements,
            (element) => element.tagName === "BUTTON"
                && element.textContent.includes(enCampaigns.CampaignAudience.saveAudience),
            "Audience save button",
        );
        expect(save.disabled).not.toBe(true);
        await act(async () => {
            interactive.dispatch("click", save);
            await Promise.resolve();
        });

        expect(setCampaignAudienceMock).toHaveBeenCalledWith(31, {
            recordType: "person",
            definition: AUDIENCE.definition,
            channel: "sms",
            purpose: "product_update",
        });
        await act(async () => root.unmount());
    });

    it("filters the real delivery panel to snapshots matching the selected message channel", async () => {
        const interactive = installInteractiveDocument();
        const { createRoot } = await import("react-dom/client");
        const root = createRoot(interactive.container);
        await act(async () => {
            root.render(
                <CampaignDelivery
                    campaignId={CAMPAIGN.id}
                    initialMessages={[EMAIL_MESSAGE]}
                    initialSends={[]}
                    snapshots={[SNAPSHOT, SMS_SNAPSHOT]}
                    access={{ manage: true, send: true, consent: true }}
                    deliveryAvailability="enabled"
                />,
            );
        });

        const messageOption = requiredElement(
            interactive.elements,
            (element) => element.tagName === "BUTTON"
                && element.getAttribute("data-select-value") === String(EMAIL_MESSAGE.id),
            "Email message option",
        );
        await act(async () => {
            interactive.dispatch("click", messageOption);
        });

        expect(interactive.container.textContent).toContain("v1 · Email · product_update · 2 included");
        expect(interactive.container.textContent).not.toContain("v2 · SMS · marketing · 3 included");
        await act(async () => root.unmount());
    });

    it("filters the real export panel to email snapshots before selection", async () => {
        const interactive = installInteractiveDocument();
        const { createRoot } = await import("react-dom/client");
        const root = createRoot(interactive.container);
        await act(async () => {
            root.render(
                <CampaignExportPanel
                    campaignId={CAMPAIGN.id}
                    initialExports={[]}
                    snapshots={[SNAPSHOT, SMS_SNAPSHOT]}
                    access={{ manage: true, send: true, consent: true }}
                    deliveryAvailability="enabled"
                />,
            );
        });

        expect(interactive.container.textContent).toContain("v1 · product_update · 2 included");
        expect(interactive.container.textContent).not.toContain("v2 · marketing · 3 included");
        const snapshotOption = requiredElement(
            interactive.elements,
            (element) => element.tagName === "BUTTON"
                && element.getAttribute("data-select-value") === String(SNAPSHOT.version),
            "Email export snapshot option",
        );
        await act(async () => {
            interactive.dispatch("click", snapshotOption);
        });
        expect(interactive.container.textContent).toContain(enCampaigns.CampaignExports.eligible);
        await act(async () => root.unmount());
    });

    it("trusts the server disclosure flag instead of stale consent access for export counts", () => {
        const restrictedExport = {
            ...COMPLETED_EXPORT,
            pushedCount: null,
            failedCount: null,
            detailedCountsKnown: true,
            detailedCountsAvailable: false,
        } satisfies CampaignAudienceExport;

        const html = renderToStaticMarkup(
            <CampaignExportPanel
                campaignId={CAMPAIGN.id}
                initialExports={[restrictedExport]}
                snapshots={[]}
                access={{ manage: true, send: true, consent: true }}
                deliveryAvailability="enabled"
            />,
        );

        expect(html).toContain(enCampaigns.CampaignExports.total);
        expect(html).toContain(enCampaigns.CampaignExports.detailedCountsRestricted);
        expect(html).not.toContain(`>${enCampaigns.CampaignExports.pushed}<`);
        expect(html).not.toContain(`>${enCampaigns.CampaignExports.notPushed}<`);
    });

    it("labels the aggregate exclusion and connector remainder as not pushed in both locales", () => {
        const catalogs = { en: enCampaigns, ja: jaCampaigns };

        for (const locale of ["en", "ja"] as const) {
            localeState.locale = locale;
            const html = renderToStaticMarkup(
                <CampaignExportPanel
                    campaignId={CAMPAIGN.id}
                    initialExports={[COMPLETED_EXPORT]}
                    snapshots={[]}
                    access={{ manage: true, send: true, consent: true }}
                    deliveryAvailability="enabled"
                />,
            );

            expect(html).toContain(`>${catalogs[locale].CampaignExports.notPushed}<`);
        }
    });

    it("shows unknown counts for a reconciled legacy export in both locales", () => {
        const legacyExport = {
            ...COMPLETED_EXPORT,
            pushedCount: null,
            failedCount: null,
            detailedCountsKnown: false,
            detailedCountsAvailable: false,
        } satisfies CampaignAudienceExport;
        const catalogs = { en: enCampaigns, ja: jaCampaigns };

        for (const locale of ["en", "ja"] as const) {
            localeState.locale = locale;
            const html = renderToStaticMarkup(
                <CampaignExportPanel
                    campaignId={CAMPAIGN.id}
                    initialExports={[legacyExport]}
                    snapshots={[]}
                    access={{ manage: true, send: true, consent: true }}
                    deliveryAvailability="enabled"
                />,
            );

            expect(html).toContain(catalogs[locale].CampaignExports.status.completed);
            expect(html).toContain(catalogs[locale].CampaignExports.detailedCountsUnknown);
            expect(html).not.toContain(`>${catalogs[locale].CampaignExports.pushed}<`);
            expect(html).not.toContain(`>${catalogs[locale].CampaignExports.notPushed}<`);
        }
    });

    it("marks an ambiguous export as delivered from the export panel", async () => {
        const reconciliationExport = {
            ...COMPLETED_EXPORT,
            status: "running",
            reconciliationRequired: true,
            pushedCount: null,
            failedCount: null,
            detailedCountsAvailable: false,
        } satisfies CampaignAudienceExport;
        reconcileCampaignExportMock.mockResolvedValue({
            ...COMPLETED_EXPORT,
            status: "completed",
        });
        const interactive = installInteractiveDocument();
        const { createRoot } = await import("react-dom/client");
        const root = createRoot(interactive.container);
        await act(async () => {
            root.render(
                <CampaignExportPanel
                    campaignId={CAMPAIGN.id}
                    initialExports={[reconciliationExport]}
                    snapshots={[]}
                    access={{ manage: true, send: true, consent: true }}
                    deliveryAvailability="enabled"
                />,
            );
        });
        const delivered = requiredElement(
            interactive.elements,
            (element) => element.tagName === "BUTTON"
                && element.textContent.includes(enCampaigns.CampaignExports.markDelivered),
            "Delivered reconciliation action",
        );

        await act(async () => {
            interactive.dispatch("click", delivered);
            await Promise.resolve();
        });

        expect(reconcileCampaignExportMock).toHaveBeenCalledWith(
            CAMPAIGN.id,
            reconciliationExport.id,
            { resolution: "delivered" },
        );
        expect(interactive.container.textContent).toContain(
            enCampaigns.CampaignExports.status.completed,
        );
        await act(async () => root.unmount());
    });

    it("confirms the not-delivered transition without retrying", async () => {
        const reconciliationExport = {
            ...COMPLETED_EXPORT,
            status: "running",
            reconciliationRequired: true,
            pushedCount: null,
            failedCount: null,
            detailedCountsAvailable: false,
        } satisfies CampaignAudienceExport;
        reconcileCampaignExportMock.mockResolvedValue({
            ...COMPLETED_EXPORT,
            status: "failed",
            pushedCount: 0,
            failedCount: COMPLETED_EXPORT.totalMembers,
        });
        const interactive = installInteractiveDocument();
        const { createRoot } = await import("react-dom/client");
        const root = createRoot(interactive.container);
        await act(async () => {
            root.render(
                <CampaignExportPanel
                    campaignId={CAMPAIGN.id}
                    initialExports={[reconciliationExport]}
                    snapshots={[]}
                    access={{ manage: true, send: true, consent: true }}
                    deliveryAvailability="enabled"
                />,
            );
        });
        const notDelivered = requiredElement(
            interactive.elements,
            (element) => element.tagName === "BUTTON"
                && element.textContent.includes(enCampaigns.CampaignExports.markNotDelivered),
            "Not-delivered reconciliation action",
        );

        await act(async () => {
            interactive.dispatch("click", notDelivered);
        });

        expect(reconcileCampaignExportMock).not.toHaveBeenCalled();
        expect(interactive.container.textContent).toContain(
            enCampaigns.CampaignExports.notDeliveredConfirmDescription,
        );

        const cancel = requiredElement(
            interactive.elements,
            (element) => element.tagName === "BUTTON"
                && element.textContent === enCampaigns.CampaignExports.cancel,
            "Not-delivered cancellation action",
        );
        await act(async () => {
            interactive.dispatch("click", cancel);
        });

        expect(reconcileCampaignExportMock).not.toHaveBeenCalled();
        expect(interactive.container.textContent).not.toContain(
            enCampaigns.CampaignExports.notDeliveredConfirmTitle,
        );

        await act(async () => {
            interactive.dispatch("click", notDelivered);
        });
        const confirm = requiredElement(
            interactive.elements,
            (element) => element.tagName === "BUTTON"
                && element.textContent === enCampaigns.CampaignExports.confirmNotDelivered,
            "Not-delivered confirmation action",
        );
        expect(confirm.getAttribute("data-variant")).toBe("destructive");
        await act(async () => {
            interactive.dispatch("click", confirm);
            await Promise.resolve();
        });

        expect(reconcileCampaignExportMock).toHaveBeenCalledWith(
            CAMPAIGN.id,
            reconciliationExport.id,
            { resolution: "not_delivered" },
        );
        expect(interactive.container.textContent).toContain(
            enCampaigns.CampaignExports.status.failed,
        );
        await act(async () => root.unmount());
    });

    it("surfaces reconciliation-required export history in both locales without false counts", () => {
        const reconciliationExport = {
            ...COMPLETED_EXPORT,
            status: "running",
            reconciliationRequired: true,
            pushedCount: null,
            failedCount: null,
            detailedCountsAvailable: false,
        } satisfies CampaignAudienceExport;
        const catalogs = { en: enCampaigns, ja: jaCampaigns };

        for (const locale of ["en", "ja"] as const) {
            localeState.locale = locale;
            const html = renderToStaticMarkup(
                <CampaignExportPanel
                    campaignId={CAMPAIGN.id}
                    initialExports={[reconciliationExport]}
                    snapshots={[]}
                    access={{ manage: true, send: true, consent: true }}
                    deliveryAvailability="enabled"
                />,
            );

            expect(html).toContain(catalogs[locale].CampaignExports.status.needs_reconciliation);
            expect(html).toContain(catalogs[locale].CampaignExports.reconciliationRequired);
            expect(html).not.toContain(`>${catalogs[locale].CampaignExports.pushed}<`);
            expect(html).not.toContain(`>${catalogs[locale].CampaignExports.notPushed}<`);
        }
    });

    it("renders an unavailable audience state and withholds every overwrite control", () => {
        const html = detailHtml(true);

        expect(html).toContain(renderToStaticMarkup(<>{enCampaigns.CampaignAudience.unavailableTitle}</>));
        expect(html).toContain(renderToStaticMarkup(<>{enCampaigns.CampaignAudience.unavailableBody}</>));
        expect(html).not.toContain(enCampaigns.CampaignAudience.noAudience);
        expect(html).not.toContain(enCampaigns.CampaignAudience.saveAudience);
        expect(html).not.toContain("segment-builder");
    });
});
