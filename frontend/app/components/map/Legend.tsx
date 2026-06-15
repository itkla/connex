'use client';

import { useState } from 'react';
import { ChevronDownIcon, ListBulletIcon } from '@heroicons/react/24/outline';
import { Button } from '@/components/ui/button';
import { useTranslations } from 'next-intl';

function Row({ swatch, label }: { swatch: React.ReactNode; label: string }) {
    return (
        <li className="flex items-center gap-2">
            <span className="flex h-4 w-6 shrink-0 items-center justify-center">{swatch}</span>
            <span className="text-xs text-muted-foreground">{label}</span>
        </li>
    );
}

function Section({ title }: { title: string }) {
    return (
        <p className="mb-1 mt-3 text-[10px] font-medium uppercase tracking-wider text-muted-foreground first:mt-0">
            {title}
        </p>
    );
}

export default function Legend() {
    const [open, setOpen] = useState(false);
    const t = useTranslations('Legend');
    if (!open) {
        return (
            <Button
                type="button"
                onClick={() => setOpen(true)}
                className="flex items-center rounded-lg border border-border bg-card/90 px-2.5 py-1.5 text-xs font-medium text-foreground shadow-md backdrop-blur transition hover:bg-card"
            >
                <ListBulletIcon className="size-4" />
            </Button>
        );
    }

    return (
        <div className="w-56 rounded-xl border border-border bg-card/95 p-3 shadow-xl backdrop-blur">
            <div className="flex items-center justify-between">
                <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">{t('title')}</p>
                <button
                    type="button"
                    onClick={() => setOpen(false)}
                    aria-label="Collapse legend"
                    className="text-muted-foreground transition hover:text-foreground"
                >
                    <ChevronDownIcon className="size-4" />
                </button>
            </div>

            <Section title="Nodes" />
            <ul className="space-y-1.5">
                <Row swatch={<span className="size-4 rounded-sm bg-brand" />} label={t('nodes.yourOrganization')} />
                <Row swatch={<span className="size-4 rounded-full bg-brand-light ring-2 ring-brand/40" />} label={t('nodes.teamMember')} />
                <Row swatch={<span className="size-4 rounded-sm bg-muted-foreground/40" />} label={t('nodes.clientCompany')} />
                <Row swatch={<span className="size-4 rounded-full border-2 border-emerald-500 dark:border-emerald-400" />} label={t('nodes.contactActive')} />
                <Row swatch={<span className="size-4 rounded-full border-2 border-dashed border-border" />} label={t('nodes.contactInactive')} />
            </ul>

            <Section title="Connections" />
            <ul className="space-y-1.5">
                <Row swatch={<span className="h-0.5 w-6 rounded bg-muted-foreground/40" />} label={t('connections.orgMembership')} />
                <Row
                    swatch={
                        <span
                            className="h-1 w-6 rounded"
                            style={{ backgroundImage: 'linear-gradient(to right, #3b82f6, #22c55e)' }}
                        />
                    }
                    label={t('connections.relationshipDeals')}
                />
                <Row swatch={<span className="w-6 border-t-2 border-dashed border-blue-500" />} label={t('connections.knownNoDeals')} />
                <Row swatch={<span className="h-px w-6 bg-border" />} label={t('connections.companyToContact')} />
            </ul>

            <Section title="Deal status" />
            <ul className="space-y-1.5">
                <Row swatch={<span className="size-2.5 rounded-full bg-blue-500" />} label={t('dealStatus.activeOpen')} />
                <Row swatch={<span className="size-2.5 rounded-full bg-emerald-500" />} label={t('dealStatus.closedWon')} />
                <Row swatch={<span className="size-2.5 rounded-full bg-red-500" />} label={t('dealStatus.closedLost')} />
            </ul>
        </div>
    );
}