import React from 'react';
import { InformationCircleIcon } from '@heroicons/react/24/outline';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';

export default function SummaryTile({ label, value, tooltip, className }: { label: string; value: string | React.ReactNode; tooltip?: React.ReactNode; className?: string }) {

    const valueElement = typeof value === 'string' ? <p className="mt-1 text-2xl font-semibold text-foreground">{value}</p> : value;

    return (
        <div className={`rounded-2xl bg-muted p-4 ring-1 ring-border${className ? ` ${className}` : ''}`}>
            <div className="flex items-center gap-1">
                <p className="text-xs uppercase tracking-wider text-muted-foreground">{label}</p>
                {tooltip && (
                    <Tooltip>
                        <TooltipTrigger asChild>
                            <InformationCircleIcon className="size-3 text-muted-foreground" />
                        </TooltipTrigger>
                        <TooltipContent>
                            <div className="flex max-w-xs flex-col gap-2">
                                <h2 className="text-sm font-medium">{label}</h2>
                                <p className="text-xs text-muted-foreground">{tooltip}</p>
                            </div>
                        </TooltipContent>
                    </Tooltip>
                )}
            </div>
            {valueElement}
        </div>
    );
}