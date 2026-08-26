'use client';

import WarmthPill from '@/app/components/records/WarmthPill';
import type { Company, RelationshipTemperature } from '@/app/lib/types';

function secondaryLine(company: Company): string {
    const industry = company.industry?.trim();
    if (industry) return industry;
    return company.website?.trim().replace(/^https?:\/\//, '').replace(/\/$/, '') ?? '';
}

/**
 * A company as one row of the viewport-forced mobile list. It mirrors the desktop company card's
 * hierarchy — name over industry — but drops the card's expandable metrics, which need a request per
 * company and a chart-sized surface neither of which belongs in a scannable phone list. The website
 * host stands in when no industry is recorded, and warmth trails the row as the decision cue.
 */
export default function CompanyListRow({
    company,
    temperature,
}: {
    company: Company;
    temperature?: RelationshipTemperature;
}) {
    const secondary = secondaryLine(company);
    return (
        <span className="flex min-w-0 items-center gap-2">
            <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium text-foreground">{company.name}</span>
                {secondary && <span className="mt-0.5 block truncate text-xs text-muted-foreground">{secondary}</span>}
            </span>
            <span className="shrink-0">
                <WarmthPill temp={temperature} />
            </span>
        </span>
    );
}
