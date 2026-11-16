import React from 'react';
import InfoTip from '@/app/components/overview/analytics/InfoTip';

export default function SummaryTile({ label, value, tooltip, className }: { label: string; value: string | React.ReactNode; tooltip?: React.ReactNode; className?: string }) {

    const valueElement = typeof value === 'string' ? <p className="mt-1 text-2xl font-semibold text-foreground">{value}</p> : value;

    return (
        <div className={`rounded-2xl border border-border bg-card p-4${className ? ` ${className}` : ''}`}>
            <div className="flex items-center gap-1">
                <p className="text-xs uppercase tracking-[0.12em] text-muted-foreground">{label}</p>
                {tooltip && (
                    <InfoTip title={label} label={label} body={tooltip} />
                )}
            </div>
            {valueElement}
        </div>
    );
}
