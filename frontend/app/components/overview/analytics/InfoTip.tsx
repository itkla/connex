import { type ReactNode } from 'react';
import { InformationCircleIcon } from '@heroicons/react/24/outline';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';

export default function InfoTip({
    title,
    body,
    label,
}: {
    title: string;
    body: ReactNode;
    label: string;
}) {
    return (
        <Tooltip>
            <TooltipTrigger asChild>
                <button
                    type="button"
                    aria-label={label}
                    className="inline-flex shrink-0 items-center rounded-full text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand"
                >
                    <InformationCircleIcon className="size-3.5" />
                </button>
            </TooltipTrigger>
            <TooltipContent>
                <div className="flex flex-col gap-2">
                    <h3 className="text-sm font-medium">{title}</h3>
                    <div className="text-xs text-background/70">{body}</div>
                </div>
            </TooltipContent>
        </Tooltip>
    );
}