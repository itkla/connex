import React from 'react';

export default function SummaryTile({ label, value, className }: { label: string; value: string | React.ReactNode; className?: string }) {

    const valueElement = typeof value === 'string' ? <p className="mt-1 text-2xl font-semibold text-foreground">{value}</p> : value;

    return (
        <div className={`rounded-2xl bg-muted p-4 ring-1 ring-border${className ? ` ${className}` : ''}`}>
            <p className="text-xs uppercase tracking-wider text-muted-foreground">{label}</p>
            {/* {value instanceof React.ReactNode ? value : <p className="mt-1 text-2xl font-semibold text-neutral-900">{value}</p>} */}
            {valueElement}
        </div>
    );
}