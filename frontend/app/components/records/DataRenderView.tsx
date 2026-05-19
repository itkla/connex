'use client';

import { useMemo, useState } from "react";
import { ChevronDownIcon, ChevronUpIcon, ChevronUpDownIcon } from "@heroicons/react/24/outline";

import ContactCard from "@/app/components/records/ContactCard";
import { Checkbox } from "@/components/ui/checkbox";

import { type Contact } from "@/app/lib/types";
import Link from "next/link";
import { useRouter } from "next/navigation";
import ContactAvatar from "@/app/components/records/contacts/ContactAvatar";
import { toast } from "sonner";
import { copyToClipboard } from "@/app/lib/utils";

export type SelectionId = string | number;

type SortKey = "name" | "email" | "phone" | "company" | "title" | "createdAt" | "updatedAt";
type SortDirection = "asc" | "desc";

const SORTABLE_COLUMNS: { key: SortKey; label: string }[] = [
    { key: "name", label: "Name" },
    { key: "email", label: "Email" },
    { key: "phone", label: "Phone" },
    { key: "company", label: "Company" },
    { key: "title", label: "Title" },
    { key: "createdAt", label: "Created" },
    { key: "updatedAt", label: "Updated" },
];

// code from w3schools, adapted for my use
const getSortValue = (item: Contact, key: SortKey): string | number | null => {
    switch (key) {
        case "company":
            return item.company?.name ?? null;
        case "createdAt":
        case "updatedAt": {
            const raw = item[key];
            const t = raw ? Date.parse(raw) : NaN;
            return Number.isNaN(t) ? null : t;
        }
        default:
            return item[key] ?? null;
    }
};

export default function DataRenderView(
    {
        data,
        displayMode,
        selectedIds,
        onSelectedIdsChange,
        onQuickEditContact,
        onDeleteContact,
    }: {
        data: any[];
        displayMode: "grid" | "table";
        selectedIds: Set<SelectionId>;
        onSelectedIdsChange: (ids: Set<SelectionId>) => void;
        onQuickEditContact?: (contact: Contact) => void;
        onDeleteContact?: (contact: Contact) => void;
    },
): React.ReactNode {
    const router = useRouter();
    const [sortKey, setSortKey] = useState<SortKey | null>(null);
    const [sortDirection, setSortDirection] = useState<SortDirection>("asc");

    const sortedData = useMemo(() => {
        if (!sortKey) return data;
        const dir = sortDirection === "asc" ? 1 : -1;
        return [...data].sort((a, b) => {
            const av = getSortValue(a, sortKey);
            const bv = getSortValue(b, sortKey);
            if (av === null && bv === null) return 0;
            if (av === null) return 1;
            if (bv === null) return -1;
            if (typeof av === "number" && typeof bv === "number") {
                return (av - bv) * dir;
            }
            return String(av).localeCompare(String(bv), undefined, { sensitivity: "base" }) * dir;
        });
    }, [data, sortKey, sortDirection]);

    const handleSort = (key: SortKey) => {
        if (sortKey === key) {
            setSortDirection((d) => (d === "asc" ? "desc" : "asc"));
        } else {
            setSortKey(key);
            setSortDirection("asc");
        }
    };

    const allSelected = sortedData.length > 0 && selectedIds.size === sortedData.length;
    const someSelected = selectedIds.size > 0 && !allSelected;

    const toggleAll = (checked: boolean) => {
        onSelectedIdsChange(checked ? new Set(sortedData.map((item) => item.id)) : new Set());
    };

    const toggleOne = (id: SelectionId, checked: boolean) => {
        const next = new Set(selectedIds);
        if (checked) next.add(id);
        else next.delete(id);
        onSelectedIdsChange(next);
    };

    return (
        <div>
            {/* TODO: switch this from a ternary to a switch statement to support more display modes */}
            {displayMode === "grid" ? (
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4 justify-center pt-8">
                    {sortedData.map((item: Contact) => (
                        <ContactCard
                            key={item.id}
                            id={item.id}
                            name={item.name}
                            title={item.title}
                            company={item.company?.name}
                            email={item.email}
                            phone={item.phone}
                            imageUrl={item.imageUrl}
                            onQuickEdit={onQuickEditContact ? () => onQuickEditContact(item) : undefined}
                            onDelete={onDeleteContact ? () => onDeleteContact(item) : undefined}
                        />
                    ))}
                </div>
            ) : (
                <div className="flex flex-col gap-4 justify-center pt-8">
                    <table className="w-full text-left">
                        <thead>
                            <tr>
                                <th className="px-4 py-2 w-10">
                                    <Checkbox
                                        checked={someSelected ? "indeterminate" : allSelected}
                                        onCheckedChange={(checked) => toggleAll(checked === true)}
                                        aria-label="Select all contacts"
                                        className="data-[state=checked]:bg-brand data-[state=checked]:border-brand-light"
                                    />
                                </th>
                                <th className="px-4 py-2 w-10 h-10"> </th>
                                {SORTABLE_COLUMNS.map(({ key, label }) => {
                                    const active = sortKey === key;
                                    const Icon = active
                                        ? sortDirection === "asc"
                                            ? ChevronUpIcon
                                            : ChevronDownIcon
                                        : ChevronUpDownIcon;
                                    return (
                                        <th key={key} className="px-4 py-2">
                                            <button
                                                type="button"
                                                onClick={() => handleSort(key)}
                                                aria-sort={active ? (sortDirection === "asc" ? "ascending" : "descending") : "none"}
                                                className="inline-flex items-center gap-1 font-semibold cursor-ns-resize"
                                            >
                                                {label}
                                                <Icon
                                                    className={`h-3.5 w-3.5 ${active ? "" : "opacity-40"}`}
                                                    aria-hidden="true"
                                                />
                                            </button>
                                        </th>
                                    );
                                })}
                            </tr>
                        </thead>
                        <tbody>
                            {sortedData.map((item: Contact) => {
                                const isSelected = selectedIds.has(item.id);
                                return (
                                    <tr
                                        key={item.id}
                                        data-state={isSelected ? "selected" : undefined}
                                        className="border-b border-gray-200 hover:bg-brand-light transition-colors duration-300 cursor-pointer data-[state=selected]:bg-brand-light/60"
                                        onClick={() => router.push(`/records/contacts/${item.id}`)}
                                    >
                                        <td className="px-4 py-2" onClick={(e) => e.stopPropagation()}>
                                            <Checkbox
                                                checked={isSelected}
                                                onCheckedChange={(checked) => toggleOne(item.id, checked === true)}
                                                aria-label={`Select ${item.name ?? "contact"}`}
                                                className="data-[state=checked]:bg-brand data-[state=checked]:border-brand-light"
                                            />
                                        </td>
                                        <td className="px-4 py-2">
                                            <ContactAvatar contact={item} />
                                        </td>
                                        <td className="px-4 py-2">{item.name}</td>
                                        <td className="px-4 py-2 hover:text-brand transition-colors duration-300" onClick={() =>
                                            copyToClipboard(item.email, 'Email') ? toast.success('Email address copied') : toast.error('Failed to copy email')
                                        }>
                                            {/* <Link href={`mailto:${item.email}`}>{item.email}</Link> */}
                                            {item.email}
                                        </td>
                                        <td className="px-4 py-2 hover:text-brand transition-colors duration-300" onClick={() =>
                                            copyToClipboard(item.phone, 'Phone') ? toast.success('Phone number copied') : toast.error('Failed to copy phone')
                                        }>
                                            {/* <Link href={`tel:${item.phone}`}>{item.phone}</Link> */}
                                            {item.phone}
                                        </td>
                                        {/* <td className="px-4 py-2">{item.company.name}</td> */}
                                        <td className="px-4 py-2 hover:text-brand transition-colors duration-300" onClick={() =>
                                            copyToClipboard(item.company?.name ?? '', 'Company') ? toast.success('Company name copied') : toast.error('Failed to copy company')
                                        }>
                                            {/* <Link href={`/records/companies/${item.company?.id}`}>{item.company?.name}</Link> */}
                                            {item.company?.name}
                                        </td>
                                        <td className="px-4 py-2">{item.title}</td>
                                        <td className="px-4 py-2">{item.createdAt}</td>
                                        <td className="px-4 py-2">{item.updatedAt}</td>
                                    </tr>
                                );
                            })}
                        </tbody>
                    </table>
                </div>
            )
            }
        </div>
    );
}