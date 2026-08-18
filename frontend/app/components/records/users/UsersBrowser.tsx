"use client";

import { useMemo } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useLocale, useTranslations } from "next-intl";
import { Squares2X2Icon, TableCellsIcon, UserGroupIcon } from "@heroicons/react/24/outline";
import { EyeIcon } from "@heroicons/react/24/solid";
import { useReducedMotion } from "motion/react";
import { Button } from "@/components/ui/button";

import RecordsRenderView from "@/app/components/records/RecordsRenderView";
import { EmptyState } from "@/app/components/EmptyState";
import Rise from "@/app/components/motion/Rise";
import RecordsSortMenu from "@/app/components/records/RecordsSortMenu";
import { SearchField, FilterBar, SegmentedToggle, type FilterChipData } from "@/app/components/filters";
import UserAvatar from "@/app/components/records/users/UserAvatar";
import UserCard from "@/app/components/records/users/UserCard";
import NewUserDialog from "@/app/components/records/users/NewUserDialog";
import { useRecordsBrowser } from "@/app/hooks/useRecordsBrowser";
import { useRecordsSort } from "@/app/hooks/useRecordsSort";
import { type ColumnDef } from "@/app/components/records/types";
import { PageHeader } from "@/app/components/PageHeader";
import { PageShell } from "@/app/components/PageShell";
import { formatDate, formatDateTime } from "@/app/lib/utils";
import { type User } from "@/app/lib/types";

const searchFields = (u: User) => [u.displayName, u.username, u.email];

export default function UsersBrowser({ users }: { users: User[] }) {
    const router = useRouter();
    const t = useTranslations("UsersBrowser");
    const tf = useTranslations("Filters");
    const locale = useLocale();
    const reduce = useReducedMotion() ?? false;
    const {
        displayMode,
        setDisplayMode,
        query,
        setQuery,
        selectedIds,
        setSelectedIds,
        filteredItems: filteredUsers,
        selectedItems: selectedUsers,
    } = useRecordsBrowser<User>({
        items: users,
        storageKey: "users:view",
        searchFields,
    });
    const { sortKey, sortDirection, onSortChange, sortState } = useRecordsSort();

    const columns: ColumnDef<User>[] = useMemo(
        () => [
            {
                key: "displayName",
                label: t("columnName"),
                getSortValue: (u) => u.displayName ?? null,
                render: (u) => u.displayName,
                widthClass: "min-w-48",
            },
            {
                key: "username",
                label: t("columnUsername"),
                getSortValue: (u) => u.username ?? null,
                render: (u) => `@${u.username}`,
            },
            {
                key: "email",
                label: t("columnEmail"),
                getSortValue: (u) => u.email ?? null,
                copyable: { label: t("columnEmail"), getValue: (u) => u.email },
            },
            {
                key: "lastLoginAt",
                label: t("columnLastLogin"),
                getSortValue: (u) => (u.lastLoginAt ? Date.parse(u.lastLoginAt) : null),
                render: (u) => (u.lastLoginAt ? formatDateTime(u.lastLoginAt, locale) : t("neverLoggedIn")),
            },
            {
                key: "createdAt",
                label: t("columnMemberSince"),
                getSortValue: (u) => (u.createdAt ? Date.parse(u.createdAt) : null),
                render: (u) => formatDate(u.createdAt, locale),
            },
        ],
        [t, locale],
    );

    const chips: FilterChipData[] = query.trim()
        ? [{ id: "q", label: tf("chipSearch", { query: query.trim() }), onRemove: () => setQuery("") }]
        : [];

    const hasActiveFilters = query.trim() !== "";
    const clearAll = () => setQuery("");

    const viewSelected = () => {
        if (selectedUsers.length === 1) {
            router.push(`/users/${selectedUsers[0].id}`);
        } else {
            selectedUsers.forEach((u) => window.open(`/users/${u.id}`, "_blank"));
        }
    };

    const selectionActions = (
        <Button variant="outline" size="sm" onClick={viewSelected}>
            <EyeIcon className="size-4" />
            {t("view")}
        </Button>
    );

    return (
        <PageShell tier="wide">
                <Rise>
                    <PageHeader title={t("heading")} actions={<NewUserDialog />} />
                </Rise>

                <Rise delay={0.06}>
                    <FilterBar
                        reduce={reduce}
                        chips={chips}
                        hasActiveFilters={hasActiveFilters}
                        onClearAll={clearAll}
                        clearAllLabel={tf("clearAll")}
                        search={
                            <SearchField
                                value={query}
                                onChange={setQuery}
                                onClear={() => setQuery("")}
                                placeholder={t("searchPlaceholder")}
                                searchAria={tf("searchAria")}
                                clearAria={tf("clearSearchAria")}
                            />
                        }
                        trailing={
                            <div className="flex items-center gap-2">
                                {displayMode === "grid" && (
                                    <RecordsSortMenu
                                        columns={columns}
                                        sortKey={sortKey}
                                        sortDirection={sortDirection}
                                        onSortChange={onSortChange}
                                    />
                                )}
                                <SegmentedToggle
                                    ariaLabel={t("displayModeAria")}
                                    value={displayMode}
                                    onChange={setDisplayMode}
                                    options={[
                                        { value: "grid", icon: <Squares2X2Icon className="size-4" />, ariaLabel: t("gridViewAria") },
                                        { value: "table", icon: <TableCellsIcon className="size-4" />, ariaLabel: t("tableViewAria") },
                                    ]}
                                />
                            </div>
                        }
                    />
                </Rise>

                <Rise delay={0.12}>
                    <RecordsRenderView<User>
                        data={filteredUsers}
                        columns={columns}
                        renderCard={(item) => <UserCard user={item} />}
                        renderAvatar={(item) => <UserAvatar user={item} />}
                        detailPath={(item) => `/users/${item.id}`}
                        displayMode={displayMode}
                        selectedIds={selectedIds}
                        onSelectedIdsChange={setSelectedIds}
                        entityLabel={t("entityLabel")}
                        selectionActions={selectionActions}
                        emptyState={
                            <EmptyState
                                icon={UserGroupIcon}
                                title={t("emptyTitle")}
                                body={t("emptyBody")}
                                action={
                                    <Button asChild variant="brand">
                                        <Link href="/settings/members">{t("emptyCta")}</Link>
                                    </Button>
                                }
                            />
                        }
                        filtersActive={hasActiveFilters}
                        onClearFilters={clearAll}
                        sortState={sortState}
                    />
                </Rise>
        </PageShell>
    );
}
