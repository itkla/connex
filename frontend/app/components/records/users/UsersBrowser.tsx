"use client";

import { useMemo } from "react";
import { useRouter } from "next/navigation";
import { useLocale, useTranslations } from "next-intl";
import { MagnifyingGlassIcon, Squares2X2Icon, TableCellsIcon } from "@heroicons/react/24/outline";
import { EyeIcon } from "@heroicons/react/24/solid";
import { Button } from "@/components/ui/button";

import RecordsRenderView from "@/app/components/records/RecordsRenderView";
import RecordsSortMenu from "@/app/components/records/RecordsSortMenu";
import UserAvatar from "@/app/components/records/users/UserAvatar";
import UserCard from "@/app/components/records/users/UserCard";
import NewUserDialog from "@/app/components/records/users/NewUserDialog";
import { useRecordsBrowser } from "@/app/hooks/useRecordsBrowser";
import { useRecordsSort } from "@/app/hooks/useRecordsSort";
import { type ColumnDef } from "@/app/components/records/types";
import { formatDate, formatDateTime } from "@/app/lib/utils";
import { type User } from "@/app/lib/types";

const searchFields = (u: User) => [u.displayName, u.username, u.email];

export default function UsersBrowser({ users }: { users: User[] }) {
    const router = useRouter();
    const t = useTranslations("UsersBrowser");
    const locale = useLocale();
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
        <div className="space-y-6">
            <div className="flex items-center justify-between">
                <h1 className="text-4xl font-extrabold">{t("heading")}</h1>
                <NewUserDialog />
            </div>

            <div className="flex items-center gap-4">
                {displayMode === "grid" && (
                    <RecordsSortMenu
                        columns={columns}
                        sortKey={sortKey}
                        sortDirection={sortDirection}
                        onSortChange={onSortChange}
                    />
                )}
                <div
                    role="group"
                    aria-label={t("displayModeAria")}
                    className="inline-flex rounded-full bg-muted p-0.5 ring-1 ring-border"
                >
                    <button
                        type="button"
                        onClick={() => setDisplayMode("grid")}
                        aria-label={t("gridViewAria")}
                        aria-pressed={displayMode === "grid"}
                        className={`flex h-7 w-7 items-center justify-center rounded-full transition ${displayMode === "grid" ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"}`}
                    >
                        <Squares2X2Icon className="size-4" />
                    </button>
                    <button
                        type="button"
                        onClick={() => setDisplayMode("table")}
                        aria-label={t("tableViewAria")}
                        aria-pressed={displayMode === "table"}
                        className={`flex h-7 w-7 items-center justify-center rounded-full transition ${displayMode === "table" ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"}`}
                    >
                        <TableCellsIcon className="size-4" />
                    </button>
                </div>

                <div className="relative ml-auto w-full max-w-sm">
                    <input
                        type="text"
                        placeholder={t("searchPlaceholder")}
                        value={query}
                        onChange={(e) => setQuery(e.target.value)}
                        className="w-full rounded-full bg-muted px-4 py-2 pr-10 text-sm text-foreground placeholder:text-muted-foreground outline-none ring-1 ring-border transition focus:ring-2 focus:ring-brand"
                    />
                    <MagnifyingGlassIcon className="pointer-events-none absolute right-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
                </div>
            </div>

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
                sortState={sortState}
            />
        </div>
    );
}