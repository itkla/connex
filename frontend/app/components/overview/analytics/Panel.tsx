import { type ReactNode } from 'react';
import { cn } from '@/lib/utils';
import InfoTip from '@/app/components/overview/analytics/InfoTip';

export default function Panel({
    title,
    subtitle,
    action,
    info,
    infoLabel,
    className,
    bodyClassName,
    children,
}: {
    title?: string;
    subtitle?: string;
    action?: ReactNode;
    info?: ReactNode;
    infoLabel?: string;
    className?: string;
    bodyClassName?: string;
    children: ReactNode;
}) {
    return (
        <section className={cn('flex h-full flex-col rounded-2xl bg-white p-6 ring-1 ring-black/5', className)}>
            {(title || action) && (
                <div className="mb-5 flex items-start justify-between gap-4">
                    <div className="min-w-0">
                        <div className="flex items-center gap-1.5">
                            {title && (
                                <h2 className="text-xs font-medium uppercase tracking-[0.12em] text-neutral-500">
                                    {title}
                                </h2>
                            )}
                            {info && title && <InfoTip title={title} body={info} label={infoLabel ?? title} />}
                        </div>
                        {subtitle && <p className="mt-1.5 truncate text-sm text-neutral-500">{subtitle}</p>}
                    </div>
                    {action ? <div className="shrink-0">{action}</div> : null}
                </div>
            )}
            <div className={cn('min-h-0 flex-1', bodyClassName)}>{children}</div>
        </section>
    );
}