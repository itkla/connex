'use client';

import { useTranslations } from 'next-intl';
import { BriefcaseIcon, BuildingOffice2Icon, UserIcon } from '@heroicons/react/24/outline';

import type { CreateDefaults } from '@/app/lib/actions/types';

/**
 * A read-only chip showing the record context a quick-create inherited from the current page (e.g. the
 * person or deal it will be linked to). Renders nothing when there is no applicable context. The
 * underlying ids are always still editable in the full dialog via "More details".
 */
export default function QuickFormContextChip({ defaults }: { defaults?: CreateDefaults }) {
    const t = useTranslations('Actions');
    const entries: { icon: typeof UserIcon; label: string }[] = [];
    if (defaults?.personLabel) entries.push({ icon: UserIcon, label: defaults.personLabel });
    if (defaults?.dealLabel) entries.push({ icon: BriefcaseIcon, label: defaults.dealLabel });
    if (defaults?.companyLabel) entries.push({ icon: BuildingOffice2Icon, label: defaults.companyLabel });
    if (entries.length === 0) return null;

    return (
        <div className="flex flex-wrap items-center gap-1.5">
            <span className="text-xs text-muted-foreground">{t('quickCreate.linkedTo')}</span>
            {entries.map(({ icon: Icon, label }) => (
                <span
                    key={label}
                    className="inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-xs font-medium text-foreground ring-1 ring-border"
                >
                    <Icon className="size-3 text-muted-foreground" />
                    {label}
                </span>
            ))}
        </div>
    );
}
