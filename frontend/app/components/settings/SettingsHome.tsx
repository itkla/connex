"use client";

import { useMemo, useState } from "react";
import { AdjustmentsHorizontalIcon } from "@heroicons/react/24/outline";
import { useTranslations } from "next-intl";

import { EmptyState } from "@/app/components/EmptyState";
import { SearchField } from "@/app/components/filters";
import Rise from "@/app/components/motion/Rise";
import { PageHeader } from "@/app/components/PageHeader";
import SettingsDirectory from "@/app/components/settings/SettingsDirectory";
import SettingsDrillDown from "@/app/components/settings/SettingsDrillDown";
import SettingsScopeSpine from "@/app/components/settings/SettingsScopeSpine";
import SettingsSearchResults from "@/app/components/settings/SettingsSearchResults";
import { useGrantedPermissions } from "@/app/hooks/usePermissions";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { resolveSettingsNavigation, searchSettingsNavigation } from "@/app/lib/settingsNavigation";
import type { InstanceCapabilities } from "@/app/lib/types";

/**
 * The unified Settings home at `/settings` (#1340 WS4.1).
 *
 * One page for every settings and administration job, rendered from the committed manifest in
 * `app/lib/settingsManifest.ts` rather than from a list restated here: authorization scopes as
 * labeled sections, the groups #1340 consolidates into as the rows, and the destinations each group
 * holds until its routes move. Wide viewports read the whole directory beside a sticky scope spine;
 * narrow ones drill one list at a time. Neither presentation is a tab strip.
 *
 * The page renders rather than forwards. `/settings` used to send the reader to Members, which the
 * epic names as the failure it is replacing, so the home is itself the destination and stays one
 * click from every settings job the reader is allowed to reach.
 *
 * @param capabilities - the resolved instance capabilities, or null when their lookup failed
 */
export default function SettingsHome({ capabilities }: { capabilities: InstanceCapabilities | null }) {
    const t = useTranslations("SettingsHome");
    const tNav = useTranslations("SettingsNav");
    const tManifest = useTranslations();
    const permissions = useGrantedPermissions();
    const { activeWorkspace } = useWorkspace();
    const [query, setQuery] = useState("");

    const scopes = useMemo(
        () =>
            resolveSettingsNavigation({
                viewer: {
                    capabilities,
                    permissions,
                    isOrgAdmin: activeWorkspace?.orgRole != null,
                },
                translate: (key) => tManifest(key),
                scopeNames: {
                    personal: tNav("scopePersonal"),
                    workspace: tNav("scopeWorkspace"),
                    organization: tNav("scopeOrganization"),
                },
                workspaceName: activeWorkspace?.name ?? null,
                organizationName: activeWorkspace?.orgName ?? null,
            }),
        [activeWorkspace, capabilities, permissions, tManifest, tNav],
    );

    const results = useMemo(() => searchSettingsNavigation(scopes, query), [scopes, query]);
    const searching = query.trim().length > 0;

    return (
        <div className="flex flex-col gap-8">
            <Rise>
                <PageHeader title={t("title")} description={t("description")} />
            </Rise>
            {scopes.length === 0 ? (
                <EmptyState
                    tone="muted"
                    icon={AdjustmentsHorizontalIcon}
                    title={t("emptyTitle")}
                    body={t("emptyBody")}
                />
            ) : (
                <div className="grid gap-x-12 gap-y-6 lg:grid-cols-[minmax(11rem,14rem)_minmax(0,1fr)]">
                    <div className="flex flex-col gap-6 lg:sticky lg:top-8 lg:self-start">
                        <SearchField
                            value={query}
                            onChange={setQuery}
                            onClear={() => setQuery("")}
                            placeholder={t("searchPlaceholder")}
                            searchAria={t("searchLabel")}
                            clearAria={t("searchClear")}
                            shortcut={null}
                            className="w-full"
                        />
                        {searching ? null : (
                            <SettingsScopeSpine
                                scopes={scopes}
                                label={t("sectionsLabel")}
                                className="hidden lg:block"
                            />
                        )}
                    </div>
                    <nav aria-label={t("navLabel")}>
                        {searching ? (
                            <SettingsSearchResults
                                results={results}
                                label={t("searchResultsLabel")}
                                emptyTitle={t("noResultsTitle", { query: query.trim() })}
                                emptyBody={t("noResultsBody")}
                                clearLabel={t("searchClear")}
                                onClear={() => setQuery("")}
                            />
                        ) : (
                            <>
                                <div className="hidden lg:block">
                                    <SettingsDirectory scopes={scopes} />
                                </div>
                                <div className="lg:hidden">
                                    <SettingsDrillDown
                                        scopes={scopes}
                                        homeName={t("title")}
                                        backLabel={(name) => t("backTo", { name })}
                                    />
                                </div>
                            </>
                        )}
                    </nav>
                </div>
            )}
        </div>
    );
}
